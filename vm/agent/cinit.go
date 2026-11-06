package main

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"sync"
	"syscall"
)

// cinit はコンテナの中の PID 1。エージェントが新しい名前空間（mount・PID・UTS・IPC）で起動する。
// rootfs を組み立てて pivot_root し、エージェントからの依頼（fd 3 の SOCK_SEQPACKET）でプロセスを起こす。
// PTY はコンテナの devpts で作り、親の側（master）を SCM_RIGHTS でエージェントに渡す。
// cinit が終わると、名前空間の中のプロセスはすべて終わる。

type ctlMessage struct {
	Type    string        `json:"type"`
	ID      int           `json:"id,omitempty"`
	Pid     int           `json:"pid,omitempty"`
	Signal  int           `json:"signal,omitempty"`
	Code    int           `json:"code,omitempty"`
	Error   string        `json:"error,omitempty"`
	Servers []string      `json:"servers,omitempty"`
	Req     *spawnRequest `json:"req,omitempty"`
}

// コンテナの中のパス（エージェントの VM のルート → コンテナの中）
var containerBinds = []struct{ host, guest string }{
	{"/data/drive", "/mnt/drive"},
	{"/data/pocket-dev", "/opt/pocket-dev"},
	{"/run/pocket", "/run/pocket-linux"},
}

func runCinit() {
	// fd 3 は親が dup2 で渡したので close-on-exec が外れている。コンテナのプロセスへ漏らさない
	syscall.CloseOnExec(3)
	ctl := os.NewFile(3, "ctl")
	send := func(m ctlMessage, files ...*os.File) error { return sendCtl(ctl, m, files...) }

	rootfs := os.Getenv("POCKET_ROOTFS")
	if err := setupContainerRoot(rootfs, os.Getenv("POCKET_HOSTNAME"), strings.Split(os.Getenv("POCKET_DNS"), ",")); err != nil {
		_ = send(ctlMessage{Type: "error", Error: err.Error()})
		os.Exit(1)
	}
	go children.loop()
	if err := send(ctlMessage{Type: "ready"}); err != nil {
		os.Exit(1)
	}

	var sendMu sync.Mutex
	for {
		m, _, err := recvCtl(ctl)
		if err != nil {
			// エージェントがいなくなった。名前空間ごと終わる
			os.Exit(0)
		}
		switch m.Type {
		case "spawn":
			if m.Req == nil {
				continue
			}
			req := *m.Req
			sp, err := spawnProcess(req)
			sendMu.Lock()
			if err != nil {
				_ = send(ctlMessage{Type: "spawned", ID: req.ID, Error: err.Error()})
				sendMu.Unlock()
				continue
			}
			_ = send(ctlMessage{Type: "spawned", ID: req.ID, Pid: sp.Pid}, sp.files()...)
			sendMu.Unlock()
			for _, f := range sp.files() {
				f.Close()
			}
			go func(id int, done <-chan syscall.WaitStatus) {
				ex := exitFromStatus(<-done)
				sendMu.Lock()
				_ = send(ctlMessage{Type: "exit", ID: id, Code: ex.Code, Signal: ex.Signal})
				sendMu.Unlock()
			}(req.ID, sp.Done)
		case "signal":
			// セッションのプロセスグループ全体に送る（setsid しているので pgid = pid）
			_ = syscall.Kill(-m.Pid, syscall.Signal(m.Signal))
		case "resolv":
			_ = writeResolvConf("/", "/etc/resolv.conf", m.Servers)
		}
	}
}

func setupContainerRoot(rootfs, hostname string, dns []string) error {
	if rootfs == "" {
		return fmt.Errorf("POCKET_ROOTFS がありません")
	}
	steps := []struct {
		what string
		fn   func() error
	}{
		{"mount の伝播を止める", func() error { return syscall.Mount("", "/", "", syscall.MS_REC|syscall.MS_PRIVATE, "") }},
		{"rootfs を mount 点にする", func() error { return syscall.Mount(rootfs, rootfs, "", syscall.MS_BIND|syscall.MS_REC, "") }},
	}
	for _, s := range steps {
		if err := s.fn(); err != nil {
			return fmt.Errorf("%s: %w", s.what, err)
		}
	}

	in := func(p string) (string, error) {
		full, err := secureJoin(rootfs, p)
		if err != nil {
			return "", err
		}
		return full, os.MkdirAll(full, 0o755)
	}
	mount := func(src, target, fstype string, flags uintptr, data string) error {
		full, err := in(target)
		if err != nil {
			return fmt.Errorf("%s: %w", target, err)
		}
		if err := syscall.Mount(src, full, fstype, flags, data); err != nil {
			return fmt.Errorf("%s を mount できません: %w", target, err)
		}
		return nil
	}

	if err := mount("proc", "/proc", "proc", syscall.MS_NOSUID|syscall.MS_NODEV|syscall.MS_NOEXEC, ""); err != nil {
		return err
	}
	if err := mount("sysfs", "/sys", "sysfs", syscall.MS_NOSUID|syscall.MS_NODEV|syscall.MS_NOEXEC, ""); err != nil {
		return err
	}
	// cgroup v2（中で Docker や systemd を動かすときに使う）。無くても続ける
	_ = mount("cgroup2", "/sys/fs/cgroup", "cgroup2", syscall.MS_NOSUID|syscall.MS_NODEV|syscall.MS_NOEXEC, "")
	// /dev は VM のものを見せる（特権コンテナ）。PTY と共有メモリはコンテナごとに分ける
	if err := mount("/dev", "/dev", "", syscall.MS_BIND|syscall.MS_REC, ""); err != nil {
		return err
	}
	if err := mount("devpts", "/dev/pts", "devpts", syscall.MS_NOSUID|syscall.MS_NOEXEC, "newinstance,ptmxmode=0666,mode=0620,gid=5"); err != nil {
		return err
	}
	ptmx, err := secureJoin(rootfs, "/dev/ptmx")
	if err != nil {
		return err
	}
	pts, _ := secureJoin(rootfs, "/dev/pts/ptmx")
	if err := syscall.Mount(pts, ptmx, "", syscall.MS_BIND, ""); err != nil {
		return fmt.Errorf("/dev/ptmx: %w", err)
	}
	if err := mount("shm", "/dev/shm", "tmpfs", syscall.MS_NOSUID|syscall.MS_NODEV, "mode=1777"); err != nil {
		return err
	}
	for _, b := range containerBinds {
		if _, err := os.Stat(b.host); err != nil {
			continue
		}
		if err := mount(b.host, b.guest, "", syscall.MS_BIND|syscall.MS_REC, ""); err != nil {
			return err
		}
	}
	if os.Getenv("POCKET_STORAGE") == "1" {
		for _, guest := range []string{"/mnt/storage", "/sdcard"} {
			if err := mount("/mnt/storage", guest, "", syscall.MS_BIND|syscall.MS_REC, ""); err != nil {
				return err
			}
		}
	}
	if err := writeResolvConf(rootfs, "/etc/resolv.conf", dns); err != nil {
		return fmt.Errorf("resolv.conf: %w", err)
	}
	if hostname != "" {
		_ = syscall.Sethostname([]byte(hostname))
	}

	if err := os.Chdir(rootfs); err != nil {
		return err
	}
	// 古いルートを新しいルートの上に重ね、すぐ外す（pivot_root(2) の「. と .」の使い方）
	if err := syscall.PivotRoot(".", "."); err != nil {
		return fmt.Errorf("pivot_root: %w", err)
	}
	if err := syscall.Unmount(".", syscall.MNT_DETACH); err != nil {
		return fmt.Errorf("古いルートを外せません: %w", err)
	}
	return os.Chdir("/")
}

func writeResolvConf(root, file string, servers []string) error {
	var b strings.Builder
	b.WriteString("# Pocket Linux が書いています（Android のネットワークの DNS）\n")
	n := 0
	for _, s := range servers {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		fmt.Fprintf(&b, "nameserver %s\n", s)
		n++
	}
	if n == 0 {
		b.WriteString("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
	}
	return writeFileIn(root, file, []byte(b.String()), 0o644)
}

// --- SOCK_SEQPACKET の上の JSON と fd の受け渡し

func sendCtl(sock *os.File, m ctlMessage, files ...*os.File) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	var oob []byte
	if len(files) > 0 {
		fds := make([]int, len(files))
		for i, f := range files {
			fds[i] = int(f.Fd())
		}
		oob = syscall.UnixRights(fds...)
	}
	raw, err := sock.SyscallConn()
	if err != nil {
		return err
	}
	var serr error
	if err := raw.Write(func(fd uintptr) bool {
		serr = syscall.Sendmsg(int(fd), b, oob, nil, 0)
		return serr != syscall.EAGAIN
	}); err != nil {
		return err
	}
	return serr
}

func recvCtl(sock *os.File) (ctlMessage, []*os.File, error) {
	buf := make([]byte, 64*1024)
	oob := make([]byte, syscall.CmsgSpace(4*4))
	raw, err := sock.SyscallConn()
	if err != nil {
		return ctlMessage{}, nil, err
	}
	var n, oobn int
	var rerr error
	if err := raw.Read(func(fd uintptr) bool {
		n, oobn, _, _, rerr = syscall.Recvmsg(int(fd), buf, oob, syscall.MSG_CMSG_CLOEXEC)
		return rerr != syscall.EAGAIN
	}); err != nil {
		return ctlMessage{}, nil, err
	}
	if rerr != nil {
		return ctlMessage{}, nil, rerr
	}
	if n == 0 {
		return ctlMessage{}, nil, os.ErrClosed
	}
	var files []*os.File
	if oobn > 0 {
		msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
		if err == nil {
			for _, msg := range msgs {
				fds, err := syscall.ParseUnixRights(&msg)
				if err != nil {
					continue
				}
				for _, fd := range fds {
					// 送る側の Fd() で blocking になっている。非ブロックにして poller で扱う
					// （こうしないと Close で Read が止まらず、裏の子が PTY を持つ間スレッドが残る）
					_ = syscall.SetNonblock(fd, true)
					files = append(files, os.NewFile(uintptr(fd), "fd"))
				}
			}
		}
	}
	var m ctlMessage
	if err := json.Unmarshal(buf[:n], &m); err != nil {
		for _, f := range files {
			f.Close()
		}
		return ctlMessage{}, nil, err
	}
	return m, files, nil
}
