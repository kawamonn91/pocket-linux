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
3. [ ] Pocket Linux: Drive 同期、ストレージ、バックアップ・復元、自動実行、IDE 用の窓口
4. [ ] pocket-dev: 接続先の追加（Pocket Linux・Drive・SSH）
5. [ ] 実機（arm64）で確認、ストア用のスクリーンショット
6. [ ] コミット・PR・マージ、T-tech Store に公開、CLAUDE.md の更新

## 現在の状態（2026-10-28 未明、初版動作確認完了・GitHub 作成へ）

- Pocket Linux の初版コード（proot エンジン・ターミナル画面・常駐サービス）を実装完了
- 中断要因だった課題をすべて解決:
  - **dpkg エラー（コード 100）**: PROOT_L2S_DIR がゲスト内から到達不能なホスト絶対パスになっていたため削除し、proot 既定の相対ディレクトリ動作に修正。
  - **IPv6 接続問題**: OkHttpClient に IPv4 優先 DNS 設定を追加し、CloudFront への接続タイムアウトを解消。
  - **TerminalView NPE**: setTypeface 呼び出し時の null チェック安全対策および初期化順の適正化。
- エミュレーター（Android 16 / x86_64）にて ubuntu:24.04 コンテナの pull・展開・初期設定（sudo, ca-certificates, curl, git 等）および一般ユーザー（user）での sudo whoami（root 取得）の正常動作を完全確認。
- これより Git 初期化、コミット、GitHub リポジトリ作成・push・マージを実施。

## メモ

- テスト用 AVD は pocketlinux（データ領域 24GB。既存の yomumemo は 6GB で足りない）
- gh auth login は設定完了済み
