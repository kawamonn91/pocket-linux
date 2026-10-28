# T-tech Store の掲載内容

`appstore/web/scripts/publish-app.mjs` に渡す内容。アイコンは `store/icon.svg`（`set-app-icon.mjs`）、
スクリーンショットはエミュレーターで使用中の画面を撮って `add-screenshot.mjs` で載せる。

- 表示名: Pocket Linux
- スラッグ: pocket-linux
- カテゴリ: tools（ツール）

## 短い説明

root なしで動く Linux コンテナとターミナル。sudo が使え、Google ドライブと双方向に同期できます

## 説明

スマホ・タブレットの中で、Ubuntu などの普通の Linux を動かすアプリです。端末の root 化は要りません。

■ できること
・Docker Hub のイメージ（ubuntu:24.04、debian、python、node など）からコンテナを作る
・一般ユーザーで sudo が使えるので、apt でツールを入れたり、Claude Code などの CLI を入れたりできる
・タブ・補助キー・日本語の文字入力欄・物理キーボードのショートカットつきのターミナル
・Google ドライブの選んだフォルダと、コンテナの ~/drive を双方向に自動で同期（PC の「Google ドライブ for desktop」のような使い方）
・コンテナの中で IDE（pocket-dev）のサーバーを動かし、ブラウザや pocket-dev アプリから ~/drive を編集・実行
・コンテナのバックアップと復元、起動時の自動実行

■ 仕組み
proot（Termux のビルド）でコンテナを動かします。Google ドライブとの同期には rclone を使うため、ログインの同意画面には「rclone」と表示されます。

■ 注意
・ARM64（64 ビット）の端末向けです
・コンテナは数 GB になることがあります。空き容量に余裕のある端末で使ってください
・Android 12 以降は、開発者向けオプションの「子プロセスの制限を無効にする」をオンにすると安定します

## リリースノート（1.0.0）

初版。コンテナ（proot）・ターミナル・常駐サービス・バックアップ、Google ドライブとの双方向同期、設定画面と IDE の窓口。
