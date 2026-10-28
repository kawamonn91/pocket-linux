# Pocket Linux

Android デバイス上で動作する、root 不要の軽量 Linux コンテナ環境・ターミナルアプリケーションです。

## 特徴

- **root 不要**: `proot`（Termux ビルド）エンジンにより、非 root の Android 環境でも安全に Linux コンテナを実行。
- **完全な sudo サポート**: 一般ユーザー（`user`）環境からパスワードなしで `sudo` が動作。
- **Docker Hub からの直接 pull**: `ubuntu:24.04` などの公式 OCI イメージを直接取得・展開。
- **組み込みターミナル**: Termux-View ベースの仮想端末・拡張キーボードを搭載。
- **常駐サービス**: フォアグラウンドサービスによる長時間のコンテナプロセス継続。
- **日本語対応**: 日本語入力・文字入力欄のサポート。

## 開発環境

- **開発言語**: Kotlin / Jetpack Compose
- **ビルドツール**: Gradle 9.7.1 / AGP 9.4.0 / NDK 27.1.12297006（端末ライブラリ `libtermux.so` のビルドに使う）
- **対応 Android**: 8.0 以降（minSdk 26、targetSdk 36）
- **アーキテクチャ**: ARM64 (`arm64-v8a`), x86_64

## ビルド方法

```bash
./gradlew assembleDebug
```

生成された APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。

単体テストは `./gradlew :app:testDebugUnitTest` で実行します。同梱する proot（Termux のビルド）は `scripts/prepare-proot.sh` で作り直せます。

## 開発の状況

初版です。Google ドライブとの同期と、IDE（pocket-dev）との連携はまだ入っていません。決定事項と進み具合は [docs/plan.md](docs/plan.md) にあります。
