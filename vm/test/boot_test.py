#!/usr/bin/env python3
"""VM を QEMU で起動して、起動からコンテナの中の sudo までを確かめる（CI の guest ジョブ）。

使い方: boot_test.py --out vm/out --rootfs ubuntu-base-*-arm64.tar.gz --work vm/test/work [--dns 10.0.2.3]

アプリの QemuLauncher.kt と同じ形で QEMU を起動し、agent_client.py で話す。
"""

import argparse
import gzip
import http.client
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from agent_client import AgentClient  # noqa: E402

CMDLINE = ("console=hvc0 root=/dev/vda rootfstype=erofs ro init=/sbin/pocket-agent quiet "
           "mitigations=off panic=-1 ip=10.0.2.15::10.0.2.2:255.255.255.0::eth0:off")
ENV = ["PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "HOME=/root", "LANG=C.UTF-8",
       "TERM=xterm-256color", "DEBIAN_FRONTEND=noninteractive"]
UBUNTU = "ubuntu"
PROXY_PORT = 18081


class Vm:
    def __init__(self, args):
        self.args = args
        self.work = args.work
        self.sock = os.path.join(self.work, "agent.sock")
        self.console = os.path.join(self.work, "console.log")
        self.proc = None
        self.client = None
        self.ready = None

    def start(self):
        if os.path.exists(self.sock):
            os.unlink(self.sock)
        out = self.args.out
        data = os.path.join(self.work, "data.img")
        argv = [
            "qemu-system-aarch64", "-M", "virt", "-cpu", "max,pauth-impdef=on", "-smp", "4", "-m", "1024",
            "-accel", "tcg,thread=multi,tb-size=256",
            "-nodefaults", "-no-user-config", "-nographic", "-no-reboot",
            "-kernel", os.path.join(out, "Image"), "-append", CMDLINE,
            # 後ろの -device が先に見つかる。system を /dev/vda にするため後ろに書く
            "-drive", f"if=none,id=data,file={data},format=raw,cache=writeback,discard=unmap,aio=threads",
            "-device", "virtio-blk-device,drive=data,serial=pocket-data",
            "-drive", f"if=none,id=sys,file={os.path.join(out, 'system.erofs')},format=raw,readonly=on",
            "-device", "virtio-blk-device,drive=sys,serial=pocket-system",
            "-netdev", f"user,id=n0,hostfwd=tcp:127.0.0.1:{PROXY_PORT}-:{PROXY_PORT}",
            "-device", "virtio-net-device,netdev=n0",
            "-device", "virtio-serial-device",
            "-chardev", f"socket,id=agent,path={self.sock},server=on,wait=off",
            "-device", "virtserialport,chardev=agent,name=pocket.agent",
            "-chardev", f"file,id=con,path={self.console},append=on",
            "-device", "virtconsole,chardev=con",
            "-device", "virtio-rng-device",
            "-device", "virtio-balloon-device,free-page-reporting=on",
        ]
        t0 = time.time()
        self.proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL)
        self.client = AgentClient(self.sock)
        self.client.hello([self.args.dns])
        self.ready = self.client.wait_event("ready", 180)
        self.boot_seconds = time.time() - t0
        return self.ready

    def call(self, req, **kw):
        return self.client.call(req, **kw)

    def run(self, cmd, container=UBUNTU, **kw):
        """コンテナの中で sh -c を動かす。"""
        return self.call({"op": "exec", "container": container, "argv": ["/bin/sh", "-c", cmd], "env": ENV}, **kw)

    def shutdown(self):
        self.call({"op": "shutdown"}, timeout=60)
        self.proc.wait(timeout=60)
        self.client.sock.close()

    def kill(self):
        self.proc.send_signal(signal.SIGKILL)
        self.proc.wait(timeout=30)
        self.client.sock.close()


failures = []


def check(name, ok, detail=""):
    print(("ok   " if ok else "FAIL ") + name + (f"  … {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        failures.append(name)


def check_exit(name, r, want=0):
    code = r.exit["code"] if r.exit else None
    check(name, code == want,
          f"exit={r.exit} stdout={bytes(r.stdout[-600:])!r} stderr={bytes(r.stderr[-600:])!r}")


def fsck(work):
    """QEMU が止まった後のディスクを e2fsck で確かめる（書き換えない）。"""
    r = subprocess.run(["/usr/sbin/e2fsck", "-fn", os.path.join(work, "data.img")],
                       capture_output=True, text=True)
    return r.returncode, (r.stdout + r.stderr)[-800:]


def tar_stream(path):
    """gzip を解いてから流す（アプリと同じ。ゲストは解凍しない）。"""
    return gzip.open(path, "rb")


def test_basics(vm):
    check("ready の version", vm.ready.get("version") == "1", str(vm.ready))
    check("/data が使える", vm.ready.get("data") is True, str(vm.ready))
    print(f"     起動にかかった時間: {vm.boot_seconds:.1f} 秒", flush=True)

    r = vm.call({"op": "exec", "argv": ["/usr/bin/rclone", "version"]})
    check_exit("VM のルートで rclone が動く", r)
    check("rclone の出力", b"rclone v" in r.stdout, bytes(r.stdout[:200]).decode("utf-8", "replace"))

    r = vm.call({"op": "df"})
    check_exit("df", r)
    df = json.loads(r.stdout)
    # data.img を 8 GiB に広げて起動したので、ゲストがファイルシステムを広げているはず
    check("データ用のディスクが広がっている", df["total"] > 7 * 2**30, str(df))


def test_container(vm, rootfs):
    t0 = time.time()
    with tar_stream(rootfs) as f:
        r = vm.call({"op": "untar", "container": UBUNTU}, stdin=f, timeout=900)
    check_exit("ubuntu-base を展開できる", r)
    print(f"     展開: {time.time() - t0:.1f} 秒 {bytes(r.stderr).decode('utf-8', 'replace').strip()}", flush=True)

    r = vm.run("cat /etc/os-release; id -u; hostname; echo pid=$$; tr '\\0' ' ' < /proc/1/cmdline; echo; uname -r")
    check_exit("コンテナでコマンドが動く", r)
    t = r.text
    check("Ubuntu が見える", "Ubuntu" in t, t)
    check("root で動く", "\n0\n" in t, t)
    check("ホスト名がコンテナの ID", "\nubuntu\n" in t, t)
    check("コンテナの PID 1 は cinit", "pocket-agent cinit" in t, t)
    check("カーネルが自前のもの", "-pocket" in t, t)

    # proot では動かなかったものが、本物として動く
    r = vm.run("mount -t tmpfs tmpfs /mnt && touch /mnt/x && umount /mnt")
    check_exit("mount できる", r)
    r = vm.run("unshare --net --pid --fork --mount-proc sh -c 'test $$ = 1'")
    check_exit("名前空間を作れる", r)
    r = vm.run("echo a > /tmp/hl && ln /tmp/hl /tmp/hl2 && stat -c %h /tmp/hl")
    check("ハードリンクが本物", r.text.strip() == "2", r.text)
    r = vm.run("stat -c '%a %U' /usr/bin/su; test -c /dev/null; getent passwd root | cut -d: -f7")
    check("setuid が展開で保たれる", r.text.startswith("4755 root"), r.text)
    r = vm.run("grep -E 'erofs|ext4|overlay|9p' /proc/filesystems | wc -l; test -e /sys/fs/cgroup/cgroup.controllers")
    check_exit("cgroup v2 が見える", r)
    check("カーネルが必要なファイルシステムを持つ", r.text.strip() == "4", r.text)
    r = vm.run("readlink /proc/self/fd/3 2>/dev/null; ls /proc/self/fd | tr '\\n' ' '")
    check("制御用の fd がコンテナに漏れない", "socket" not in r.text, r.text)


def test_io(vm):
    # 標準入力・標準出力・標準エラー出力・終了コード
    blob = os.urandom(1024 * 1024)
    r = vm.call({"op": "exec", "container": UBUNTU, "argv": ["/bin/cat"], "env": ENV}, stdin=blob)
    check_exit("1 MiB を cat で往復できる", r)
    check("往復した内容が同じ", bytes(r.stdout) == blob, f"{len(r.stdout)} bytes")
    r = vm.run("echo out; echo err >&2; exit 3")
    check_exit("終了コードが届く", r, 3)
    check("標準出力と標準エラー出力を分ける", r.text == "out\n" and bytes(r.stderr) == b"err\n",
          f"{r.text!r} {bytes(r.stderr)!r}")
    # 裏で動き続ける子が出力を持っていても、EXIT は届く
    t0 = time.time()
    r = vm.run("sleep 30 & echo started", timeout=20)
    check_exit("裏の子がいても終わる", r)
    check("裏の子を待たない", time.time() - t0 < 15 and "started" in r.text, f"{time.time() - t0:.1f} 秒")

    # シグナルと CLOSE
    chan, res = vm.client.open({"op": "exec", "container": UBUNTU, "argv": ["/bin/sleep", "4243"], "env": ENV})
    time.sleep(1)
    vm.client.signal(chan, 15)
    res.done.wait(30)
    check("シグナルで止まる", res.exit and res.exit.get("signal") == 15, str(res.exit))
    chan, res = vm.client.open({"op": "exec", "container": UBUNTU, "argv": ["/bin/sleep", "4242"], "env": ENV})
    time.sleep(1)
    vm.client.close(chan)
    time.sleep(1)
    r = vm.run("for p in /proc/[0-9]*; do tr '\\0' ' ' < $p/cmdline 2>/dev/null; echo; done | grep -c 'sleep 424[2]' || true")
    check("CLOSE でプロセスが止まる", r.text.strip() in ("0", ""), r.text)

    r = vm.call({"op": "put", "container": UBUNTU, "path": "/tmp/put/hello.txt", "mode": 0o640}, stdin=b"hello\n")
    check_exit("put", r)
    r = vm.call({"op": "get", "container": UBUNTU, "path": "/tmp/put/hello.txt"})
    check_exit("get", r)
    check("put した内容を get できる", bytes(r.stdout) == b"hello\n", repr(bytes(r.stdout)))
    r = vm.run("stat -c %a /tmp/put/hello.txt")
    check("put の権限", r.text.strip() == "640", r.text)
    r = vm.call({"op": "get", "container": UBUNTU, "path": "/no/such/file"})
    check("無いファイルの get は失敗する", r.exit["code"] != 0, str(r.exit))

    r = vm.call({"op": "exec", "container": "bad/id", "argv": ["/bin/true"], "env": ENV})
    check("正しくない ID を断る", r.exit["code"] != 0 and "ID" in (r.exit.get("error") or ""), str(r.exit))
    r = vm.call({"op": "exec", "container": "nothing", "argv": ["/bin/true"], "env": ENV})
    check("中身が無いコンテナを断る", r.exit["code"] != 0, str(r.exit))

    r = vm.call({"op": "dns", "servers": ["9.9.9.9"]})
    check_exit("dns", r)
    time.sleep(1)
    r = vm.run("cat /etc/resolv.conf")
    check("resolv.conf を書き直す", "nameserver 9.9.9.9" in r.text, r.text)
    vm.call({"op": "dns", "servers": [vm.args.dns]})
    time.sleep(1)


def test_pty(vm):
    chan, res = vm.client.open({
        "op": "exec", "container": UBUNTU, "argv": ["/bin/bash", "--noprofile", "--norc", "-i"], "env": ENV,
        "pty": True, "cols": 100, "rows": 30, "cwd": "/root",
    })

    def wait_output(needle, timeout=30):
        deadline = time.time() + timeout
        while time.time() < deadline:
            if needle in res.stdout:
                return True
            time.sleep(0.05)
        return False

    vm.client.write(chan, b"stty size; tty; pwd; echo mark1\n")
    check("PTY: 窓の大きさ", wait_output(b"30 100"), bytes(res.stdout[-300:]))
    check("PTY: 端末がある", wait_output(b"/dev/pts/"), bytes(res.stdout[-300:]))
    check("PTY: cwd", wait_output(b"/root"), bytes(res.stdout[-300:]))
    vm.client.resize(chan, 120, 40)
    time.sleep(0.5)
    vm.client.write(chan, b"stty size\n")
    check("PTY: 窓の大きさの変更", wait_output(b"40 120"), bytes(res.stdout[-300:]))
    vm.client.write(chan, b"sleep 1000\n")
    time.sleep(1)
    vm.client.write(chan, b"\x03")  # Ctrl-C は端末の中の前面のプロセスだけに届く
    time.sleep(1)
    vm.client.write(chan, "echo 日本語$((40+2))\n".encode())
    check("PTY: Ctrl-C のあとも動く・日本語", wait_output("日本語42".encode()), bytes(res.stdout[-300:]))
    vm.client.write(chan, b"exit 7\n")
    res.done.wait(30)
    check("PTY: 終了コード", res.exit and res.exit.get("code") == 7, str(res.exit))


def test_user_and_sudo(vm):
    t0 = time.time()
    r = vm.run("apt-get update -qq && apt-get install -y -qq --no-install-recommends sudo ca-certificates >/dev/null",
               timeout=900)
    check_exit("apt-get で sudo を入れられる", r)
    print(f"     apt-get: {time.time() - t0:.1f} 秒", flush=True)
    r = vm.run("useradd -m -s /bin/bash user && usermod -aG sudo user")
    check_exit("user を作れる", r)
    r = vm.call({"op": "put", "container": UBUNTU, "path": "/etc/sudoers.d/90-pocket", "mode": 0o440},
                stdin=b"user ALL=(ALL) NOPASSWD:ALL\n")
    check_exit("sudoers を置ける", r)
    r = vm.run("su -l user -c 'whoami; sudo -n whoami'")
    check_exit("su と sudo が動く", r)
    check("user から sudo で root", r.text.split() == ["user", "root"], f"{r.text!r} {bytes(r.stderr)!r}")


def test_events_and_network(vm):
    r = vm.run("echo 'https://example.com/login' > /run/pocket-linux/open/a.tmp && "
               "mv /run/pocket-linux/open/a.tmp /run/pocket-linux/open/a.url")
    check_exit("URL の依頼を置ける", r)
    try:
        e = vm.client.wait_event("open-url", 20)
        check("URL を開くイベント", e.get("url") == "https://example.com/login", str(e))
    except TimeoutError as ex:
        check("URL を開くイベント", False, str(ex))

    chan, res = vm.client.open({"op": "watch", "path": "/data/drive", "exclude": [".git"]})
    time.sleep(1)
    r = vm.run("mkdir -p /mnt/drive/sub && sleep 1 && touch /mnt/drive/sub/a /mnt/drive/b")
    check_exit("/mnt/drive に書ける", r)
    deadline = time.time() + 10
    while time.time() < deadline and b"/sub/a" not in res.stdout:
        time.sleep(0.1)
    check("watch が変化を知らせる", b"/sub/a" in res.stdout and b"/b" in res.stdout, bytes(res.stdout))
    vm.client.close(chan)

    # Drive のログインと同じ経路: ブラウザ → slirp のポート転送 → proxy → VM の 127.0.0.1 の rclone
    srv, srv_res = vm.client.open({"op": "exec", "argv": ["/usr/bin/rclone", "authorize", "drive", "--auth-no-open-browser"]})
    prx, _ = vm.client.open({"op": "proxy", "listen": f"10.0.2.15:{PROXY_PORT}", "target": "127.0.0.1:53682"})
    url = None
    deadline = time.time() + 40
    while time.time() < deadline and url is None:
        found = re.search(rb"http://127\.0\.0\.1:53682(/auth\?state=[A-Za-z0-9_\-]+)", bytes(srv_res.stdout + srv_res.stderr))
        url = found.group(1).decode() if found else None
        time.sleep(0.2)
    check("rclone がログインの URL を出す", url is not None, bytes(srv_res.stderr[-400:]))
    ok, detail = False, ""
    deadline = time.time() + 30
    while url and time.time() < deadline and not ok:
        try:
            conn = http.client.HTTPConnection("127.0.0.1", PROXY_PORT, timeout=10)
            conn.request("GET", url)
            resp = conn.getresponse()
            detail = f"{resp.status} {resp.getheader('Location')}"
            ok = "accounts.google.com" in (resp.getheader("Location") or "")
            conn.close()
        except (OSError, http.client.HTTPException) as ex:
            detail = str(ex)
        if not ok:
            time.sleep(1)
    check("ポート転送と proxy で VM の中のサーバーに届く", ok, detail)
    vm.client.close(srv)
    vm.client.close(prx)

    # VM のルートの名前解決（rclone が使う）
    r = vm.call({"op": "get", "path": "/etc/resolv.conf"})
    check("VM のルートにも DNS の設定がある", f"nameserver {vm.args.dns}".encode() in r.stdout, bytes(r.stdout))
    r = vm.call({"op": "mkdir", "path": "/data/drive/made/by/agent"})
    check_exit("mkdir", r)
    r = vm.run("test -d /mnt/drive/made/by/agent")
    check_exit("mkdir したフォルダがコンテナから見える", r)
    r = vm.call({"op": "rm", "path": "/data/drive/made"})
    check_exit("rm", r)
    r = vm.run("test ! -e /mnt/drive/made")
    check_exit("rm したフォルダが消える", r)


def test_backup(vm):
    r = vm.call({"op": "du", "container": UBUNTU})
    check_exit("du", r)
    check("du の値", json.loads(r.stdout)["bytes"] > 50 * 2**20, r.text)

    t0 = time.time()
    r = vm.call({"op": "tar", "container": UBUNTU, "path": "/", "exclude": ["/proc", "/sys", "/dev"]}, timeout=900)
    check_exit("バックアップ（tar）", r)
    backup = bytes(r.stdout)
    seconds = time.time() - t0
    print(f"     tar: {len(backup) >> 20} MiB を {seconds:.1f} 秒（{(len(backup) >> 20) / max(seconds, 0.1):.1f} MiB/秒）",
          flush=True)
    path = os.path.join(vm.work, "backup.tar")
    with open(path, "wb") as f:
        f.write(backup)
    with tarfile.open(path) as tf:
        names = tf.getnames()
    check("tar に中身がある", any(n.endswith("usr/bin/sudo") for n in names), str(names[:5]))
    check("tar が除外を守る", not any(n.startswith("./proc/") for n in names))
    check("除外したフォルダ自体は残る", "./proc" in names, str(names[:5]))

    with open(path, "rb") as f:
        r = vm.call({"op": "untar", "container": "restored"}, stdin=f, timeout=900)
    check_exit("バックアップから復元", r)
    r = vm.run("su -l user -c 'sudo -n whoami'", container="restored")
    check("復元したコンテナで sudo が動く", r.text.strip() == "root", f"{r.text!r} {bytes(r.stderr)!r}")
    r = vm.run("echo $$; hostname", container="restored")
    check("2 つ目のコンテナは別の名前空間", "restored" in r.text, r.text)

    r = vm.call({"op": "stop", "container": "restored"})
    check_exit("コンテナを止める", r)
    r = vm.call({"op": "delete", "container": "restored"})
    check_exit("コンテナを消す", r)
    r = vm.run("true", container="restored")
    check("消したコンテナは起動できない", r.exit["code"] != 0, str(r.exit))


def test_persistence(vm):
    r = vm.run("echo persisted > /root/keep && sync")
    check_exit("ファイルを書く", r)
    vm.shutdown()
    check("shutdown で QEMU が終わる", vm.proc.returncode == 0, str(vm.proc.returncode))
    code, log = fsck(vm.work)
    check("正常に止めた後のディスクがきれい", code == 0, log)

    vm.start()
    check("2 回目の起動", vm.ready.get("data") is True, str(vm.ready))
    print(f"     2 回目の起動: {vm.boot_seconds:.1f} 秒", flush=True)
    r = vm.run("cat /root/keep; su -l user -c 'sudo -n whoami'")
    check("再起動してもファイルが残る", r.text.split() == ["persisted", "root"], f"{r.text!r} {bytes(r.stderr)!r}")

    # 強制終了（Android に殺されたとき）
    chan, _ = vm.client.open({"op": "exec", "container": UBUNTU,
                              "argv": ["/bin/sh", "-c", "while :; do dd if=/dev/urandom of=/root/big bs=1M count=64 2>/dev/null; done"],
                              "env": ENV})
    time.sleep(5)
    vm.kill()
    vm.start()
    check("強制終了の後に起動できる", vm.ready.get("data") is True, str(vm.ready))
    r = vm.run("cat /root/keep; rm -f /root/big")
    check("強制終了の後もファイルが残る", r.text.strip() == "persisted", r.text)
    vm.shutdown()
    code, log = fsck(vm.work)
    check("強制終了から立ち直ったディスクがきれい", code == 0, log)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="vm/out")
    ap.add_argument("--rootfs", required=True)
    ap.add_argument("--work", default="vm/test/work")
    ap.add_argument("--dns", default="10.0.2.3")
    args = ap.parse_args()

    shutil.rmtree(args.work, ignore_errors=True)
    os.makedirs(args.work)
    # アプリと同じ: ひな形を疎ファイルとして書き出し、大きくしてから起動する（ゲストが広げる）
    data = os.path.join(args.work, "data.img")
    subprocess.run(f"gzip -dc {args.out}/data.img.gz | cp --sparse=always /dev/stdin {data}", shell=True, check=True)
    os.truncate(data, 8 * 2**30)
    print(f"data.img の実際の大きさ: {os.stat(data).st_blocks * 512 >> 20} MiB", flush=True)

    vm = Vm(args)
    code = 1
    try:
        vm.start()
        test_basics(vm)
        test_container(vm, args.rootfs)
        test_io(vm)
        test_pty(vm)
        test_user_and_sudo(vm)
        test_events_and_network(vm)
        test_backup(vm)
        test_persistence(vm)
        code = 1 if failures else 0
    except Exception as ex:  # noqa: BLE001
        print(f"FAIL テストが途中で止まりました: {ex!r}", flush=True)
        import traceback
        traceback.print_exc()
    finally:
        if vm.proc and vm.proc.poll() is None:
            vm.proc.kill()
        print("--- console.log（最後の 80 行）")
        try:
            with open(vm.console, encoding="utf-8", errors="replace") as f:
                print("".join(f.readlines()[-80:]))
        except OSError as ex:
            print(ex)
    print(f"失敗: {failures}" if failures else "すべて通りました")
    sys.exit(code)


if __name__ == "__main__":
    main()
