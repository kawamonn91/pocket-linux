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
- **ビルドツール**: Gradle 8.13 / AGP 8.9.1 / NDK r28c
- **ターゲット**: Android 14+ (API 34〜36)
- **アーキテクチャ**: ARM64 (`arm64-v8a`), x86_64

## ビルド方法

```bash
./gradlew assembleDebug
```

生成された APK は `app/build/outputs/apk/debug/app-debug.apk` に出力されます。
