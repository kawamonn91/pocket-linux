# pocket-agent の制御用チャンネルの約束事

アプリ（`AgentChannel.kt`）と VM の中の `pocket-agent`（`vm/agent/`）の間の約束事。
テスト用の実装は `vm/test/agent_client.py`。

通信路は virtio-serial のポート `pocket.agent` が 1 本だけ。QEMU 側は unix ソケットで、アプリがつなぐ。
この 1 本の上で、ターミナルのタブ・ファイルの転送・コマンドの実行を並べて動かす。

## フレーム

```
[長さ u32 BE][種類 u8][チャンネル u32 BE][中身]
```

長さは「種類」から後のバイト数（5 以上、5 + 64 KiB 以下）。中身が 64 KiB を超えるときは分けて送る。

| 値 | 名前 | 向き | チャンネル | 中身 |
|---|---|---|---|---|
| 1 | HELLO | アプリ → ゲスト | 0 | JSON `{time, dns}` |
| 2 | OPEN | アプリ → ゲスト | 新しい番号 | JSON（下の「op」） |
| 3 | DATA | 両方向 | そのチャンネル | アプリ → ゲストは標準入力、ゲスト → アプリは標準出力 |
| 4 | EOF | アプリ → ゲスト | そのチャンネル | なし。標準入力を閉じる |
| 5 | RESIZE | アプリ → ゲスト | そのチャンネル | JSON `{cols, rows}`（PTY のとき） |
| 6 | SIGNAL | アプリ → ゲスト | そのチャンネル | JSON `{signal}`。プロセスグループに送る |
| 7 | CLOSE | アプリ → ゲスト | そのチャンネル | なし。チャンネルを捨て、プロセスを止める |
| 8 | EXIT | ゲスト → アプリ | そのチャンネル | JSON `{code, signal, error}`。そのチャンネルの最後のフレーム |
| 9 | STDERR | ゲスト → アプリ | そのチャンネル | 標準エラー出力（PTY のときは使わない） |
| 10 | EVENT | ゲスト → アプリ | 0 | JSON `{type, ...}` |

## つなぎ方

1. アプリは QEMU のソケットにつなぎ、HELLO を送る
   - `time`: 端末の時刻（UNIX 時刻のミリ秒）。ゲストの時計を合わせる
   - `dns`: コンテナの `/etc/resolv.conf` に書く DNS サーバー（Android は slirp の 10.0.2.3 では名前解決できないので、アプリが教える）
2. ゲストは EVENT `{type: "ready", version, uptimeMs, data}` を返す
   - `version`: エージェントの版。アプリは自分の知っている版と比べる
   - `data`: `/data`（データ用のディスク）を使えるなら true
3. HELLO を受けると、ゲストは前のチャンネルのプロセスをすべて止める（アプリが落ちてつなぎ直したときの片付け）。**動いているコンテナは止めない**

チャンネルの番号はアプリが決める（1 以上。同じ接続の中で使い回さない）。

OPEN の直後に DATA や EOF を送ってよい（ゲストは OPEN を受けた時点でチャンネルを用意する）。

## op（OPEN の中身）

すべて `{"op": "...", ...}` の形。結果は EXIT の `code`（0 が成功）と `error`。

| op | 項目 | すること |
|---|---|---|
| `exec` | `container`, `hostname`, `argv`, `env`, `cwd`, `pty`, `cols`, `rows`, `shared` | コマンドを動かす。`shared` は VM のルートのコマンドを umask 0 で動かす（Drive の同期フォルダに書く rclone 用）。`container` が空なら VM のルート（rclone など）、あればそのコンテナの中（動いていなければ起動する）。`env` は全部アプリが渡す |
| `untar` | `container`, `path` | 標準入力の tar を展開する。OCI のレイヤーとして扱う（ホワイトアウト、所有者、権限、ハードリンク、デバイスファイル、拡張属性）。gzip は解いて流す |
| `tar` | `container`, `path`, `exclude` | `path` の下を tar にして標準出力へ流す（バックアップ。`exclude` は `path` からの相対パスで、フォルダならフォルダ自体は残して中身を入れない） |
| `put` | `container`, `path`, `mode` | 標準入力の内容をファイルに書く（一度別名に書いて置き換える。上書きなら元の所有者を引き継ぐ） |
| `get` | `container`, `path` | ファイルの内容を標準出力へ流す |
| `mkdir` | `container`, `path`, `mode` | フォルダを作る（親もまとめて）。VM のルートにはシェルが無いので、このために用意している。`mode` を付けると、すでにあるフォルダもその権限にする |
| `share` | `container`, `path` | `path` の下を、どのコンテナのユーザーからも読み書きできる権限にする（フォルダは 777、ファイルは `rw` を足す。リンクは触らない）。Drive の同期フォルダの前の版の分を直すのに使う |
| `rm` | `container`, `path` | ファイルやフォルダを消す（中身ごと） |
| `stop` | `container` | コンテナを止める（中のプロセスはすべて終わる。止まるのを待ってから EXIT） |
| `delete` | `container` | コンテナを止めて消す |
| `df` | | データ用のディスクの使用量を JSON で返す `{total, free, used}` |
| `du` | `container` | コンテナの使用量を JSON で返す `{bytes}` |
| `settime` | `time` | ゲストの時計を合わせる（スリープ明けなど） |
| `dns` | `servers` | VM のルートと、動いているコンテナの `resolv.conf` を書き直す（ネットワークが変わったとき） |
| `watch` | `path`, `exclude` | フォルダの下の変化を見張り、変わったパスを 1 行ずつ送る（Drive の同期のきっかけ）。CLOSE まで続く |
| `proxy` | `listen`, `target` | VM の中の TCP をつなぐ（rclone のログインのときなど）。CLOSE まで続く |
| `shutdown` | | コンテナを止め、`/data` を読み取り専用にして VM の電源を切る。EXIT を返してから止まる |

`untar`・`tar`・`put`・`get`・`mkdir`・`rm` は、`container` を省くと VM のルートで作業する。コンテナを指定すると、その rootfs に chroot してから作業する（イメージの中のシンボリックリンクで外に書けない）。コンテナが動いていなくても使える。

## イベント（EVENT）

| type | 項目 | 意味 |
|---|---|---|
| `ready` | `version`, `uptimeMs`, `data` | HELLO への返事 |
| `open-url` | `url` | コンテナの `xdg-open` が URL を開いてほしいと言った。コンテナからは `/run/pocket-linux/open/*.url` に書く（`mv` で置く） |

## 流量の制御

専用の仕組みは無い。標準入力の待ち行列（チャンネルごとに 64 フレーム）があふれると、ゲストはポートを読むのをやめるので、アプリの書き込みが止まる。

- 1 つのチャンネルが詰まると全部が待つ。`untar` のように流し込む処理の間も、待つのは前に並んでいる数百 KB の分だけなので、ターミナルの入力の遅れは小さい
- ターミナル（PTY）は読み手が止まっていても全体を止めないよう、待ち行列（256 フレーム）があふれた分を捨てる

## コンテナ

- ディスク上の場所: `/data/containers/<id>/rootfs`。`<id>` は `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`
- 名前空間: mount・PID・UTS・IPC。ネットワークは VM と共有する（特権コンテナ。中で Docker も動かせる）
- コンテナの PID 1 は `pocket-agent cinit`。これが終わると中のプロセスはすべて終わる
- 中に見えるもの: `/proc`、`/sys`、`/sys/fs/cgroup`、VM の `/dev`（PTY と共有メモリはコンテナごと）、`/mnt/drive`（`/data/drive`）、`/opt/pocket-dev`（`/data/pocket-dev`）、`/run/pocket-linux`（`/run/pocket`）、端末のストレージがあれば `/mnt/storage` と `/sdcard`
- `/etc/resolv.conf` は起動のたびに書く

## ディスク

| ディスク | serial | 中身 |
|---|---|---|
| `system.erofs` | `pocket-system` | `/`（読み取り専用）。カーネルが `root=/dev/vda` で mount する |
| `data.img` | `pocket-data` | `/data`（ext4）。エージェントが serial で探す。ファイルが大きくなっていれば起動時に広げる |

QEMU の `virt` マシンでは、**コマンドラインの後ろにある `-device` が先に見つかる**（`/dev/vda` になる）。`system.erofs` の `-device` を `data.img` より後ろに書く。

## ネットワーク

カーネルの `ip=10.0.2.15::10.0.2.2:255.255.255.0::eth0:off` で設定する（DHCP を待たない）。エージェントは lo を上げるだけ。
