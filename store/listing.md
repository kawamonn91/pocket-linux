# T-tech Store の掲載内容

`appstore/web/scripts/publish-app.mjs` に渡す内容。アイコンは `store/icon.svg`（`set-app-icon.mjs`）、
スクリーンショットはエミュレーターで使用中の画面を撮って `add-screenshot.mjs` で載せる。

- 表示名: Pocket Linux
- スラッグ: pocket-linux
- カテゴリ: tools（ツール）

## 短い説明

root なしで動く本物の Linux（軽量 VM）とターミナル。sudo・Docker が使え、Google ドライブと双方向に同期できます

## 説明

スマホ・タブレットの中で、Ubuntu などの普通の Linux を動かすアプリです。端末の root 化は要りません。
アプリの中で軽量な VM を動かすので、カーネルの機能がすべて本物です。

■ できること
・Docker Hub のイメージ（ubuntu:24.04、debian、python、node など）からコンテナを作る
・一般ユーザーで sudo が使えるので、apt でツールを入れたり、Claude Code などの CLI を入れたりできる
・Docker・systemd の部品・iptables・mount・ネットワークの名前空間など、普通のコンテナ環境では動かないものも動く
・タブ・補助キー・日本語の文字入力欄・物理キーボードのショートカットつきのターミナル
・Google ドライブの選んだフォルダと、コンテナの ~/drive を双方向に自動で同期（PC の「Google ドライブ for desktop」のような使い方）
・コンテナの中で IDE（pocket-dev）のサーバーを動かし、ブラウザや pocket-dev アプリから ~/drive を編集・実行
・コンテナのバックアップと復元、起動時の自動実行

■ 仕組み
QEMU（CPU をソフトウェアで再現する方式）で Linux カーネルを動かし、その中でコンテナを動かします。Google ドライブとの同期には rclone を使うため、ログインの同意画面には「rclone」と表示されます。

■ 注意
・ARM64（64 ビット）の端末向けです
・CPU を使う処理（コンパイル、npm install など）は端末の本来の速さより数倍〜20 倍ほど遅くなります。シェル・git・apt などには十分です
・コンテナは数 GB になることがあります。空き容量に余裕のある端末で使ってください

## リリースノート（2.0.0）

コンテナの動かし方を proot から軽量 VM に変えました。Docker・iptables・mount・ハードリンクなどが本物になり、プロセスが多くても Android に強制終了されなくなりました。前の版のコンテナと Drive の同期フォルダは、トップ画面のお知らせから移せます。

## リリースノート（1.0.0）

初版。コンテナ（proot）・ターミナル・常駐サービス・バックアップ、Google ドライブとの双方向同期、設定画面と IDE の窓口。
