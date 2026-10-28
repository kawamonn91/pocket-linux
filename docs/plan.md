# Pocket Linux と pocket-dev（IDE）改修の計画

2026-10-27 にかわもんと決めた内容と、作業の進み具合。

## 目的

Google ドライブ上のコードを、スマホ・タブレットで編集して実行できるようにする。
PC で「Drive for desktop で `G:\` にマウント → VS Code で編集 → ターミナルで実行」しているのと同じ使い方を、Android で再現する。
Termux は sudo が使えないため、自作する。

## 全体の構成

| 役割 | アプリ | リポジトリ |
|---|---|---|
| コンテナ（Linux）・ターミナル・Drive の同期（PC の Drive for desktop に当たる） | **Pocket Linux**（`com.ttech.pocketlinux`、新規） | kawamonn91/pocket-linux（公開） |
| エディタ（PC の VS Code に当たる） | **pocket-dev**（`com.ttech.pocketdev`、既存を改修） | kawamonn91/ttech-store の `native-apps/pocket-dev` |
| IDE のサーバーと画面 | pocket-dev の `agent/`・`web/` | kawamonn91/pocket-dev（公開に切り替える） |

## 決定事項

### Pocket Linux

- コンテナは proot（Termux のビルドを同梱）。root 化は不要
  - 実行ファイルは `lib*.so` として APK に入れ、nativeLibraryDir から実行する（Android 10 以降の W^X 制限のため）
  - イメージは Docker Hub などのレジストリから pull する（arm64 / x86_64）。コンテナは複数作れる。既定は `ubuntu:24.04`
  - ハードリンクは Android で作れないので、展開時はコピーに置き換え、実行時は `--link2symlink` を使う
    - 代わりの隠しファイルは rootfs の `.l2s` にまとめる（`PROOT_L2S_DIR`）。フォルダが無いと `ln` が失敗するので、起動の前に作る
    - rootfs と `PROOT_L2S_DIR` は実体のパス（`canonicalPath`）で渡す。アプリから見た `/data/user/0` は `/data/data` の別名で、proot は `-r` を実体のパスにしてリンクの中身を書くため、別名のままだとリンクをたどれない（`run-as` では `/data/user/0` が実体なので再現しない）
    - proot はリンクの中身に端末側の絶対パスを書く。バックアップでは、書き出すときにコンテナ内のパスにし、復元するときに新しい rootfs のパスへ戻す（`L2sLinks`）
- コンテナ内は一般ユーザー `user`（パスワードなし sudo）。ログインは `proot -0` → `su -l user`。proot が setuid を再現するので、本物の sudo が動く（2026-10-27 にエミュレーターで確認）
- Claude Code（native インストーラー）と agy（Antigravity CLI）が入ることをエミュレーターで確認済み
- Google Drive: root なしでは FUSE が使えないため、rclone で選んだフォルダと `~/drive/<名前>` を**双方向に自動同期**する
  - ローカルの変更は数秒後に送り、Drive 側の変更は数分おきとアプリを開いたときに取り込む
  - `node_modules`・`.venv` などは同期しない（既定の除外リストを画面で編集できる）
  - rclone の Linux 版は Android 上で直接動かす。名前解決（`/etc/resolv.conf` が無い）はアプリ内の CONNECT プロキシ（`HTTPS_PROXY`）で、証明書は `SSL_CERT_DIR` で解決する（確認済み）
  - ログインは rclone の Google 承認済みの仕組みを使う（同意画面には「rclone」と出る）。自前の Google Cloud 登録は不要
- 初版に入れる: 端末ストレージ参照（`~/storage`）、コンテナのバックアップ・復元（tar.gz）、物理キーボードのショートカット、起動時の自動実行
- 日本語入力: 端末に直接打つほか、確定してから送る「文字入力欄」を用意する
- IDE 用の窓口: pocket-dev のサーバー（Python）をコンテナ内で動かす。既定は端末内のみ、設定で LAN・Tailscale から許可（トークン必須）
- Claude Code・agy のログイン用に、コンテナ内の `xdg-open`（`BROWSER`）を Android のブラウザにつなぐ

### pocket-dev（IDE）

- 接続先を4種類にする
  1. PC などのサーバー（今の動き）
  2. 同じ端末の Pocket Linux（編集＋実行。Pocket Linux が同期した Drive のフォルダを編集する）
  3. Google Drive に直接（Pocket Linux が無いとき。rclone を同梱。編集のみ）
  4. SSH（SFTP で編集、IDE 内のターミナルも SSH 先。パスワード・鍵、踏み台 ProxyJump、Cloudflare Tunnel 経由 `ssh.kawamonn.com`、Tailscale の機器）
- 2〜4 は、アプリ内のローカルサーバーが pocket-dev と同じ API（`/api/fs/*`、`/ws/pty`）を出し、WebView で同梱の `web/` を表示する

## 進め方

1. [x] 技術検証（エミュレーター x86_64 / Android 16）: proot・sudo・Claude Code・agy・rclone
2. [x] Pocket Linux: エンジン（pull・展開・起動）、ターミナル画面、常駐サービス（一般ユーザー user・sudo whoami 動作確認完了）
3. [ ] Pocket Linux: Drive 同期（済み）、ストレージ、バックアップ・復元（済み）、自動実行（済み）、IDE 用の窓口
4. [ ] pocket-dev: 接続先の追加（Pocket Linux・Drive・SSH）
5. [ ] 実機（arm64）で確認、ストア用のスクリーンショット
6. [ ] T-tech Store に公開（コミット・PR・マージと CLAUDE.md への追加は 2026-10-28 に済み）

## 現在の状態（2026-10-28）

- 初版（エンジン・ターミナル画面・常駐サービス・バックアップ）が main に入っている。公開リポジトリ kawamonn91/pocket-linux
- エミュレーター（Android 16 / x86_64）で、ubuntu:24.04 の pull・展開・初期設定（sudo・ca-certificates・curl・git など）と、一般ユーザー `user` からの `sudo whoami` を確認済み
- 2026-10-28 の修正（ブランチ `fix/l2s-backup-refactor`）:
  - dpkg の失敗（終了コード 100）の原因は2つ。`PROOT_L2S_DIR` のフォルダが無く `ln` が失敗していたことと、`PROOT_L2S_DIR` が `/data/user/0/…`（別名）で proot の rootfs `/data/data/…`（実体）と食い違い、perl のハードリンクの所有者の設定が「ファイルが無い」で失敗していたこと。一度は設定ごと消して動かしていたが、それだとハードリンクを作ったフォルダ（Drive と同期する場所も）に `.l2s.*` が残るので、設定を戻してフォルダを作り、パスを実体にそろえた
  - バックアップを別のコンテナとして復元すると、ハードリンクの代わりのリンクが切れる問題を直した（上の `L2sLinks`）
  - 効いていなかった IPv4 優先の DNS 設定を消した（OkHttp 5 は Happy Eyeballs で IPv6 → IPv4 の順に並べ直すため）
  - 端末画面の NPE は、文字サイズを書体より先に設定して防ぐ（Termux の TerminalView は元のまま）
- 未着手: 実機（arm64）での確認、配色（ライトテーマの tertiary）、設定画面・Drive 画面（仮置き）、Drive 同期（rclone の同梱）、IDE の窓口、pocket-dev 側の改修、テスト（今は `L2sLinks` だけ）、ストア公開

## A① rclone の同梱（2026-10-28）

- Drive・手元のファイル・双方向同期（bisync）・ログイン（authorize）・lsjson・mkdir・version だけを入れた rclone v1.75.2 を `scripts/build-rclone.sh` で作り、`jniLibs/*/librclone.so` として同梱する（ARM64 23MB、x86_64 25MB。公式の全機能入りは約 80MB）。Go 1.27.3、`-trimpath -buildvcs=false` で、同じ版の Go なら同じ実行ファイルになる（CI の `rclone.yml` が確かめる）
- 名前解決は `ConnectProxy`（127.0.0.1 だけで待ち受け、起動ごとの合言葉つき、宛先は 443 番だけ）、証明書は `SSL_CERT_DIR`（Android 14 からの Conscrypt の場所と、古い場所の両方）
- ログイン（`rclone authorize drive`）・フォルダ一覧・bisync の呼び出しは `drive/Rclone.kt`

## A② Drive の双方向同期（2026-10-28）

- 画面: Drive 画面でログイン → 「フォルダを追加」で Drive のフォルダを選び、端末側の名前（`~/drive/<名前>`）を決める。組ごとに状態・今すぐ同期・最初からやり直す・同期をやめる。「同期しないもの」を編集できる（変えると全部の組を合わせ直す）
- 仕組み（`drive/DriveSync.kt`）: `rclone bisync`。端末側の変更は `TreeWatcher`（フォルダごとの FileObserver）で見張り、5 秒落ち着いたら送る。Drive 側は 3 分おきとアプリを開いたとき。同じ組の同期は重ねない。組の一覧は `rclone/pairs.json`、除外は `rclone/excludes.txt`
- ログインの間は常駐サービスを動かす（ブラウザに切り替わった間にアプリが止められ、rclone が同意の結果を受け取れなかったため）。もう一度押すとやり直し、5 分で打ち切る
- ハードリンク: proot はハードリンクの中身をコンテナの `.l2s` に移すので、Drive のフォルダのファイルがコンテナを指すリンクになる。同期の前に `HardLinkGuard` が普通のファイルに戻す。git はオブジェクトをハードリンクで書くので、コンテナの `/etc/gitconfig` に `core.createObject = rename` を入れる（作成時と起動のたび）
- エミュレーターと本物の Drive（`G:\マイドライブ\pocket-linux-test`）で確認: ログイン、最初の同期（日本語の名前・下のフォルダ）、端末→Drive（作成・追記・ハードリンク・`node_modules` の除外）、Drive→端末、削除

## メモ

- テスト用 AVD は `pocketlinux`（データ領域 24GB。既存の `yomumemo` は 6GB で足りない）
- `gh` は 2026-10-28 時点でログイン済み
