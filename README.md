# Pocket Linux

Android デバイス上で動作する、root 不要の Linux 環境・ターミナルアプリケーションです。アプリの中で軽量な VM（QEMU の TCG）を動かし、その中でコンテナを動かします。

## 特徴

- **root 不要で本物の Linux**: 自前のカーネルが動くので、名前空間・cgroup・mount・iptables・Docker・ハードリンク・`/proc` がすべて本物。
- **完全な sudo サポート**: 一般ユーザー（`user`）環境からパスワードなしで `sudo` が動作。
- **Docker Hub からの直接 pull**: `ubuntu:24.04` などの公式 OCI イメージを直接取得・展開。
- **組み込みターミナル**: Termux-View ベースの仮想端末・拡張キーボードを搭載。
- **常駐サービス**: フォアグラウンドサービスで VM を動かし続ける。誰も使わなくなると VM は自動で止まる。
- **日本語対応**: 日本語入力・文字入力欄のサポート。
- **Google ドライブと双方向に自動同期**: 選んだ Drive のフォルダを、コンテナの `~/drive/<名前>` と同期（VM の中の rclone）。
- **IDE の窓口**: コンテナの中で [pocket-dev](https://github.com/kawamonn91/pocket-dev) のサーバーを動かし、`~/drive` を編集・実行できる。

## 開発環境

- **開発言語**: Kotlin / Jetpack Compose
- **ビルドツール**: Gradle 9.7.1 / AGP 9.4.0 / NDK 27.1.12297006（端末ライブラリ `libtermux.so` のビルドに使う）
- **VM の生成物**: カーネル・`system.erofs`・`libqemu.so` は GitHub Releases に置き、ビルドのときに `vm/artifacts.properties` の版と SHA-256 で取ってくる（`vm/` と [docs/vm-plan.md](docs/vm-plan.md)）
- **対応 Android**: 8.0 以降（minSdk 26、targetSdk 36）
- **アーキテクチャ**: ARM64 (`arm64-v8a`), x86_64

## ビルド方法

```bash
./gradlew assembleDebug
```

生成された APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

単体テストは `./gradlew :app:testDebugUnitTest` で実行します。PR では GitHub Actions で同じテスト・lint・ビルドが走り、通ると自動でマージされます（[docs/ci.md](docs/ci.md)）。

## Google のログインの設定

rclone 共有の client_id は 2026 年中に使えなくなり（[rclone の案内](https://rclone.org/drive/#making-your-own-client-id)）、利用者全員で上限を分け合うので `rateLimitExceeded` も出やすいため、自前の OAuth クライアントを使います（pocket-dev アプリと同じクライアント）。

- リポジトリの直下に `google-oauth.properties` を置く（Git の対象外。公開リポジトリには入れない）

  ```properties
  clientId=xxxx.apps.googleusercontent.com
  clientSecret=xxxx
  ```

- 無くてもデバッグ版はビルドでき、Drive の画面はログインできないと表示します。リリース版のビルドは、無いと止まります
- 2.0.0 までのログイン（rclone 共有のクライアント）はトークンを更新できないので引き継がず、トップ画面のお知らせからログインし直してもらいます。同期するフォルダの設定と中身は残ります

## 開発の状況

決定事項と進み具合は [docs/plan.md](docs/plan.md) と [docs/vm-plan.md](docs/vm-plan.md) にあります。アプリと VM の中のエージェントの約束事は [docs/vm-protocol.md](docs/vm-protocol.md) です。同梱するもののライセンスは、アプリの「設定 → オープンソースのライセンス」と `third_party/` にあります。
