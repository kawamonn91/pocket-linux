# Pocket Linux 2.0: 軽量 VM 方式への移行計画（案）

2026-11-05 作成。状態: **実行中**。

2026-11-05 にかわもんの判断で、Phase 0（実機での計測）は飛ばして Phase 1 から進めることにした。
そのため「6. 決めること」は推奨のとおりに決めた（proot は 2.0 で消す、Drive の同期はゲストの中で行う、生成物は GitHub Releases に置く）。
速さの計測は Phase 5・6 の実機の確認で行う。

## 進み具合

| Phase | 状態 | メモ |
|---|---|---|
| 0 | 飛ばした | |
| 1 ゲスト | 完了（PR #11） | CI の QEMU で、起動からコンテナの中の sudo・バックアップ・強制終了からの回復まで確かめている |
| 2 QEMU | 完了（PR #12） | 依存を静的にリンクした 1 ファイル（約 14 MB） |
| 3 アプリ | 完了 | エミュレーターで確認（下の「確認の記録」） |
| 4 残りの機能 | 完了 | IDE・`xdg-open`・Drive（ゲストの中の rclone）・端末のストレージ（9p）・バックアップ・1.x からの移行 |
| 5 軽量化と安定性 | ほぼ完了 | 自動停止（5 分）、zram、free page reporting、FITRIM、時計合わせ、ネットワークが変わったときの DNS の書き直し。**アプリ経由の名前解決はやっていない**（Private DNS に従わない。端末の DNS に直接問い合わせる） |
| 6 公開 | ストアには公開済み。**実機の確認待ち** | proot は消した。2.0.0 を 2026-11-06 に公開（実機の確認の前に公開した）。2.0.1 で Drive を自前の OAuth に変えた |
| 7 AVF | 未定 | 実機が対応しているかを確かめてから |

計画からの変更:

- **proot との切り替えの隠し設定は作らなかった。** 両方を動かす抽象化のコストが高く、2.0 で proot を消すと決めたので、1 つの PR で VM に切り替えた。公開は手動なので main は壊れない
- **`vm/` と `guest/`:** ゲスト一式は `vm/` に置いた（カーネル設定は `vm/kernel/`、エージェントは `vm/agent/`）
- **カーネルなどは assets に入れ、初回の起動で展開する。** ゲストは常に arm64 なので、ABI ごとの jniLibs に入れると 2 倍になるため。`.gz` のアセットは AGP が勝手に展開するので `data.img.gzip` という名前で入れる
- **1.x の移行はお知らせから利用者が始める。** 時間がかかり、途中は容量を 2 倍使うため。移すまで 1.x のコンテナは開けず、Drive の同期も止まる

残っているリスク:

- 端末の空き容量が尽きると、QEMU は VM を止める（`werror` の既定）。今は通知していない。ディスクの上限（既定 32 GB、設定で変えられる）を端末の空きより小さくしておくしかない
- **実機で動かした記録がまだ無い。** 確かめるもの: arm64 の `libqemu.so` の起動（エミュレーターで動くのは x86_64 版。CI の `guest.yml` は Ubuntu の QEMU で、`qemu.yml` はビルドまで）、9p（Armv9.2 の端末で落ちる報告がある）、速さ（4.5 の目標）、裏での動作
- **1.x のコンテナの移行は、成功する経路を動かした記録がまだ無い。** エミュレーターでは x86_64 の中身を arm64 のゲストに移すので、`exec format error` で失敗する（失敗しても元のコンテナは残る）。成功すると元の rootfs は消える

## 1. 結論

- **うまくいく見込みは高い。** Android の普通のアプリ（root なし）から使える VM の方法は、事実上 **QEMU のソフトウェア CPU エミュレーション（TCG）** だけだが、これで Linux カーネルごと動かせる。Podroid（GPLv2）が同じ方式で、root なしの Alpine VM を Android で動かしている実例がある
- 代わりに **CPU を使う処理は 5〜20 倍遅くなる**（Podroid の資料）。シェル・git・apt・ネットワークの道具には十分。コンパイル・`npm install`・Node や Bun（Claude Code）の起動は重くなる。**どこまで実用になるかを、最初に実機で計る（Phase 0）**
- 軽くするための設計の要点:
  - VM の中に Linux の配布物（Alpine など）を置かない。**カーネル → 自作の小さなエージェント（PID 1）** だけで起動する
  - コンテナ（Docker Hub のイメージ）は、**1 台の VM の中で、本物の名前空間を使って**動かす
  - これで今の使い方（ubuntu:24.04 が既定、複数のコンテナ、`user` + sudo）をそのまま残す

## 2. 精査: proot の何が足りないのか

「Android のカーネルに手を出せない」という見立ては正しい。正確には、Android がアプリのプロセスに対して、名前空間・cgroup・mount・一部のシステムコール（seccomp）・ハードリンク（SELinux）などを許していない。proot はそれをシステムコールの横取りで「それらしく見せる」だけなので、限界がある。VM ならゲストが自分のカーネルを持つので、これらがすべて本物になる。

| proot でできない・不安定なこと | 原因 | VM で |
|---|---|---|
| Docker・podman、systemd、cgroups | 名前空間・cgroup をアプリが作れない | できる |
| iptables・nftables、`ip netns`、tc、WireGuard、tun | カーネルの機能をアプリから使えない | できる（VM の中のネットワークに限る） |
| mount、loop デバイス、FUSE | 権限がない | できる |
| ハードリンク、本物のファイル所有者、setuid | SELinux と proot の見せかけ（`.l2s`、`L2sLinks`、`HardLinkGuard`） | 本物になる |
| `/proc` の一部 | Android が読ませない（`FakeProc`） | 本物になる |
| io_uring など一部のシステムコール | Android の seccomp | 使える |
| プロセスが多いと強制終了される | コンテナ内の各プロセスが本物の子プロセスで、phantom process killer（32 個まで）の対象 | 子プロセスは QEMU 1 つだけ |
| 速さ | ptrace ですべてのシステムコールを横取りするので、システムコールの多い処理が遅い | CPU の処理は遅くなる。システムコールはゲストのカーネルが処理するので速い |

VM でも変わらないこと:

- ネットワークは QEMU の内蔵 NAT（slirp）。外から VM の中へは、ポート転送したポートにしか届かない。Wi-Fi の L2 には出られない
- GPU は使えない

おまけ: VM の中では `ip netns`・veth・bridge・nftables・WireGuard が本物なので、ネットワークの実習をスマホの中でできる。

## 3. Android で VM を動かす方法の比較

| 方法 | root なしで動くか | 速さ | 判断 |
|---|---|---|---|
| KVM（`/dev/kvm`）を直接使う | × アプリからは開けない | ほぼ本物 | 不可 |
| AVF（Android Virtualization Framework。Pixel の「Linux ターミナル」と同じ仕組み） | △ 権限 `MANAGE_VIRTUAL_MACHINE` は `signature\|preinstalled\|development`。adb で付与すれば普通のアプリでも使える（Podroid の例）。対応する端末は主に Pixel 8 以降 | ほぼ本物 | 主の方法にはできない。Phase 7 で任意の高速化として検討する |
| **QEMU TCG**（CPU をソフトウェアで再現） | ○ どの arm64 端末でも（Podroid・Vectras VM・Termux で実績あり） | CPU の処理は 5〜20 倍遅い | **採用** |
| User-Mode Linux | × arm64 の上では動かない | — | 不可 |
| gVisor | × ptrace と名前空間か KVM が要る | — | 不可 |

実機（Xiaomi 2407FPN8ER。Xiaomi 14T Pro、SoC は Dimensity 9300+ とみられる）で AVF が使えるかは、Phase 0 で確かめる。

## 4. 設計

### 4.1 全体の構成

```
Android のアプリ（com.ttech.pocketlinux）
├─ 画面・常駐サービス（Kotlin）
│   ├─ ターミナル ─────┐
│   ├─ IDE の窓口      ├── 制御用のソケット（アプリ専用のフォルダの unix ソケット）
│   ├─ Drive の同期 ───┘        │
│   └─ VM の管理 ── QMP のソケット │（ポート転送・停止・状態）
│                                │
└─ QEMU（libqemu.so。TCG。子プロセスはこれ 1 つ）
    ├─ virtio-serial … 制御用のチャンネル（pocket-agent とつながる）
    ├─ virtio-blk vda … system.erofs（読み取り専用。APK に入れる。アプリの更新で入れ替わる）
    ├─ virtio-blk vdb … data.img（ext4。書き込み用。疎ファイルで、使った分だけ容量を使う）
    ├─ virtio-net     … slirp（NAT。127.0.0.1 などへのポート転送）
    ├─ virtio-9p      … 端末のストレージ（任意）
    ├─ virtio-rng・virtio-balloon・hvc0（起動ログ）
    └─ ゲスト: Linux カーネル（arm64。必要な機能は組み込み、モジュールなし）
         └─ pocket-agent（PID 1。Go）
              ├─ コンテナ「ubuntu」 … /data/containers/<id>（名前空間 + pivot_root）
              ├─ コンテナ「alpine」 … …
              └─ rclone（Drive の同期。Phase 4 で決める）
```

### 4.2 ゲストの中身

- **カーネル**: その時点の LTS の版に固定する。QEMU の `virt` マシン用に、virtio-mmio・ext4・erofs・overlayfs・9p・FUSE・名前空間・cgroup v2・netfilter（nftables と iptables 互換）・bridge・veth・tun・WireGuard・zram・BPF を組み込む。PCI・USB・画面・音・無線などは入れない。`CONFIG_HZ=100` と tickless にして、何もしていないときの CPU を減らす
- **initramfs も init システム（OpenRC・systemd）も使わない。** カーネルが system.erofs を `/` にして、`pocket-agent` を直接 PID 1 として起動する
- **system.erofs**（lz4 で圧縮した読み取り専用のファイルシステム）: pocket-agent、rclone、CA 証明書だけ。mkfs はせずひな形（data.img.gz）を使い、広げるのはエージェントが ioctl で行い、壊れたときの回復は ext4 のジャーナルに任せるので、e2fsprogs と busybox は入れない
- **pocket-agent**（Go。rclone のビルドで使っている Go の環境をそのまま使える）の仕事:
  - 起動: `/proc`・`/sys`・`/dev` の mount、data.img の mount（ひな形は `data.img.gz` として APK に入れる。ファイルが大きくなっていれば広げる）、時刻、ゾンビプロセスの回収。ネットワークはカーネルの `ip=` で 10.0.2.15/24 に固定する（DHCP を待たない）
  - 制御用のチャンネル: コマンドの実行（PTY あり・なし）、画面の大きさの変更、シグナル、終了コード、ファイルの送受信、tar の展開・書き出し、URL を開く依頼（ゲスト → アプリ）、時刻合わせ、停止、使用量の報告
  - コンテナの実行: コンテナごとに名前空間（mount・PID・UTS・IPC）を作り、`pivot_root` で中に入る。ネットワークは VM と共有する。ユーザー自身の VM なので特権コンテナにする（中で Docker も動かせる）
  - 一般ユーザー `user` の作成（今の `RootfsSetup` の処理をゲスト側に移す）

### 4.3 QEMU の起動のしかた（案）

```
libqemu.so -M virt -cpu max,pauth-impdef=on -smp 4 -m 1024
  -accel tcg,thread=multi,tb-size=256
  -nodefaults -no-user-config -nographic
  -kernel Image -append "console=hvc0 root=/dev/vda rootfstype=erofs ro init=/sbin/pocket-agent quiet mitigations=off panic=-1 ip=10.0.2.15::10.0.2.2:255.255.255.0::eth0:off"
  -no-reboot
  -drive if=none,id=data,file=data.img,format=raw,cache=writeback,discard=unmap,aio=threads
  -device virtio-blk-device,drive=data,serial=pocket-data
  -drive if=none,id=sys,file=system.erofs,format=raw,readonly=on   -device virtio-blk-device,drive=sys,serial=pocket-system
  -netdev user,id=n0 -device virtio-net-device,netdev=n0
  -device virtio-serial-device
  -chardev socket,id=agent,path=<files>/vm/agent.sock,server=on,wait=off -device virtserialport,chardev=agent,name=pocket.agent
  -chardev file,id=con,path=<files>/vm/console.log -device virtconsole,chardev=con
  -device virtio-rng-device -device virtio-balloon-device,free-page-reporting=on
  -qmp unix:<files>/vm/qmp.sock,server=on,wait=off
```

- 機器はすべて virtio-mmio（`virtio-*-device`）にする。PCI を使わないので、オプション ROM などの付属ファイルが要らない
- `virt` マシンでは後ろの `-device` が先に見つかる。system.erofs を `/dev/vda` にするため、data.img より後ろに書く。data.img はエージェントが serial で探す
- `-cpu` は Phase 0 で `max,pauth-impdef=on`・`max,pauth=off,sve=off`・`cortex-a76` を比べて決める（ポインター認証や SVE の再現は重い）
- ゲストは端末の種類にかかわらず **aarch64 だけ**にする。カーネル・system.erofs・エージェントは 1 種類で済み、Docker Hub からは常に arm64 のイメージを取る。x86_64 版の QEMU は、エミュレーター（AVD）での開発用に同梱する

### 4.4 今の機能はどうなるか

| 今の機能 | 今のしくみ（proot） | VM 方式 |
|---|---|---|
| コンテナの作成 | Kotlin がレイヤーを展開（`LayerExtractor`・`FileOps`） | Kotlin はダウンロード（`RegistryClient` はそのまま）と gzip を解くところまで。tar をエージェントに流し、ゲストで本物の所有者・ハードリンク・デバイスファイル付きで展開する |
| ログイン・sudo | `proot -0` → `su -l user`、proot が setuid を再現 | 本物の root と sudo |
| ハードリンク | `.l2s`・`L2sLinks`・`HardLinkGuard`・`core.createObject=rename` | 本物（ext4）。古い処理は 1.x からの移行にだけ使う |
| `/proc` | `FakeProc` | 本物 |
| ターミナル | proot を PTY で起動（Termux の `TerminalSession` の子プロセス） | ゲストの PTY を制御用のチャンネルでつなぐ。アプリ側に子プロセスを作らない |
| 起動時の自動実行 | 裏のタブ | 同じ（エージェントで実行） |
| IDE の窓口 | コンテナで Python のサーバー、127.0.0.1:ポート | 同じ。slirp のポート転送で 127.0.0.1（LAN を許可したら 0.0.0.0）に出す |
| `xdg-open`（`pocket-open`） | bridge フォルダのファイルを監視（`UrlBridge`） | エージェントからのメッセージ |
| DNS | `resolv.conf` を毎回書く | 同じ（slirp の 10.0.2.3 は Android では動かないため）。Phase 5 でアプリ経由の名前解決にする |
| Drive の同期 | Android 上の rclone。`/mnt/drive` に bind | 推奨: ゲストの中の rclone で、data.img の `/data/drive` と同期する。代替: 今のまま Android 側で同期し、9p で見せる（Phase 4 の前に決める） |
| 端末のストレージ | `/storage/emulated/0` を bind | 9p で見せる（任意の機能にする） |
| バックアップ・復元 | tar.gz（`.l2s` のリンクを書き換える） | エージェントが tar を流す |
| phantom process killer | コンテナの各プロセスが対象。すぐ 32 個を超える | 対象は QEMU 1 つ（Phase 5 で 0 にもできる） |

### 4.5 軽さの目標（Phase 5 で計る）

| 項目 | 目標 |
|---|---|
| 起動してシェルが出るまで（実機） | 10 秒以内（コンテナの起動は一瞬） |
| メモリ（シェル 1 つ、アプリ全体） | 300 MB 以下。VM のメモリは既定 1 GB（端末の RAM が 8 GB 未満なら 512 MB）だが、使った分だけ確保され、free page reporting で Android に返す |
| 何もしていないときの CPU | 1 % 以下 |
| APK の増え方（arm64 版） | +25 MB 以内（proot の分は減る） |
| ディスク | data.img は疎ファイル。ゲストで fstrim すると縮む |

## 5. 作業計画

main を常に公開できる状態に保つため、VM の方式は 2.0 までデバッグ版の隠し設定で proot と切り替えられるようにし、段階ごとに PR を出す。目安は Claude Code との作業日で、**合計 15〜22 日（3〜4 週間）**。

### Phase 0: 実機での技術検証（0.5〜1 日）

目的: 速さ・安定性・9p が実機で足りるかを、作り込む前に確かめる。

1. 端末の確認（adb）: Android の版、RAM、SoC（`ro.soc.model`）、AVF の有無（`ro.boot.hypervisor.vm.supported`、`pm list features` の virtualization）
2. 実機に Termux（F-Droid か GitHub 版）を入れ、`qemu-system-aarch64-headless` と openssh を入れて sshd を起動する。PC から `adb forward` と ssh でつなぎ、計測は Claude が行う（ユーザーの手作業は Termux のインストールと sshd の起動だけ）
   - Termux の QEMU（11.1.3）は依存が 20 以上あるのでアプリには同梱しないが、計測には使える。Termux もアプリの制限の中で動くので、TCG・slirp・9p の振る舞いは同じ
3. Alpine の virt 用カーネルで起動し、ubuntu-base の rootfs に chroot して、今の Pocket Linux（proot）と同じ項目を計る（最終形に近い形）
   - 起動時間、キー入力の反応、`apt-get install -y git`、Python の CPU 処理、大きな tar.gz の展開、`git clone` と `git status`、`claude --version`、小さな `npm install`、QEMU のメモリ使用量、`ping`
   - `-cpu` の 3 案と `tb-size` の比較
4. 9p の読み書き（Armv9.2 の端末で QEMU の 9p が落ちる報告がある。Dimensity 9300+ も Armv9.2）
5. 常駐させて裏で 30 分放置し、VM が殺されないか（phantom process killer）。電池の減りも見る

続けるかどうかの判断:

| 項目 | 続ける目安 |
|---|---|
| シェルの操作 | 入力の遅れを感じない |
| `apt-get install -y git` | proot の 3 倍以内 |
| Claude Code | 起動が 15 秒程度で、会話の操作ができる |
| 裏での動作 | 30 分殺されない（常駐 + 案内している開発者向けオプションの設定で） |
| 9p | 落ちない（落ちたら Drive はゲストに移す。ストレージは任意の機能にする） |

成果物: `docs/vm-spike.md`（数値の記録と判断）。

### Phase 1: ゲスト側（カーネル・system.erofs・pocket-agent）（3〜4 日）

- `vm/kernel/required.config`・`extra.config` と `vm/scripts/build-kernel.sh`（版とソースの SHA-256 を固定し、同じ入力から同じ物ができるようにする）
- `vm/agent/`（Go）: 4.2 の仕事。制御用のチャンネルの約束事は `docs/vm-protocol.md` に書く
- `vm/scripts/build-system.sh`: system.erofs とデータ用ディスクのひな形（data.img.gz）を作る
- CI に `guest.yml` を足す: arm64 の Linux ランナーの qemu-system-aarch64（TCG）でゲストを起動し、ubuntu:24.04 の rootfs を入れて `sudo whoami` が `root` になるまでを確かめる（`vm/test/boot_test.py`）
- 生成物（カーネル・system.erofs）の置き場所を決める（推奨: GitHub Releases に置き、ビルドのときに SHA-256 を固定して取ってくる。Git の履歴が重くならない）
- 完了条件: CI の QEMU で、起動からコンテナのシェルまで動き、テストが通る

### Phase 2: Android 用の QEMU（2〜4 日。いちばん不確実）

- `scripts/build-qemu.sh`: NDK でクロスビルドする。QEMU 11.1 系。glib・libslirp・pcre2・libffi は静的にリンクして、1 つの実行ファイルにする（proot のときの名前の付け替えが要らない）。Bionic に合わせる修正は Termux の patch を参考にする
- 構成: `--target-list=aarch64-softmmu`、既定の機能と機器を外し（`virt` と virtio だけ）、slirp と 9p だけを有効にする。16 KB ページの端末のために `-Wl,-z,max-page-size=16384`
- 出力: `jniLibs/arm64-v8a/libqemu.so` と `jniLibs/x86_64/libqemu.so`（今の proot と同じく nativeLibraryDir から実行する）
- CI に `qemu.yml` を足す（`rclone.yml` と同じく、同じ入力から同じ物ができることを確かめる）
- 完了条件: エミュレーターと実機で、アプリから QEMU を起動して Phase 1 のゲストが動く。速さが Phase 0 の計測と同じくらい

### Phase 3: アプリへの組み込み（4〜5 日）

- `vm/` パッケージ: `VmManager`（起動・停止・状態）、`QemuLauncher`（引数の組み立て）、`AgentChannel`（制御用のチャンネル。複数のセッションを 1 本で送る）、`Qmp`（ポート転送・停止）。組み立てとチャンネルには単体テストを付ける
- ターミナル: `TerminalSession` に「子プロセスの代わりにストリームを使う」口を足す（terminal-emulator はリポジトリの中にあるので直せる）
- `ContainerManager` を VM 用にする（ダウンロード → gzip を端末側で解く → tar をゲストへ）
- 起動中の表示（「Linux を起動しています」）、VM の設定（メモリ・CPU の数・ディスクの上限）、起動ログ（console.log）の表示
- 完了条件: 実機で、コンテナの作成 → `sudo whoami`、複数のタブ、画面の回転と大きさの変更、文字入力欄からの日本語、起動時の自動実行が動く

### Phase 4: 残りの機能の移し替え（3〜4 日）

- IDE の窓口（slirp のポート転送。pocket-dev の `agent/`・`web/` はエージェントでゲストに送る）
- `xdg-open`（エージェントのメッセージにする）
- Drive の同期（Phase 0 の結果を見て決める。推奨はゲストの中の rclone。ログインの `rclone authorize` は、その間だけポートを転送して行う）
- 端末のストレージ（9p。任意の機能）
- バックアップ・復元。1.x のバックアップ（tar.gz）を VM のコンテナとして取り込む（移行用。ファイルの所有者は取り込むときに作り直す）
- 完了条件: plan.md の「初版に入れる」機能がすべて VM で動く

### Phase 5: 軽量化と安定性（2〜3 日）

- スリープ明けの時刻合わせ（Android の時計に合わせる）
- 名前解決をアプリ経由にする（Android の Private DNS や VPN の設定に従わせる）
- balloon の free page reporting、ゲストの zram、使っていないときの自動停止、起動の短縮
- phantom process killer: Phase 0 や実機で殺されることがあれば、QEMU を共有ライブラリにして、別プロセス（`android:process=":vm"`）のサービスから JNI で動かす。こうすると子プロセスが 0 になり、対象から外れる
- 強制終了から立ち直れること: ext4 のジャーナルとバリアは外さず、起動のたびに fsck する
- 完了条件: 4.5 の目標を満たす

### Phase 6: 2.0 の公開（1 日）

- proot と関係するコード（`ProotLauncher`・`FakeProc`・`L2sLinks`・`HardLinkGuard`・proot の jniLibs など）を消す（Phase 0 で「残す」と決めた場合を除く）
- ライセンスの表示と、ソース一式の置き場所（GPL の義務。下の 7 章）
- README・plan.md・`store/listing.md` の更新、versionName を 2.0.0 に
- 実機での確認、ストア用のスクリーンショット（決まった手順で）、T-tech Store に公開

### Phase 7（任意）: AVF で速くする（2〜3 日）

- AVF に対応した端末（主に Pixel 8 以降）だけで使う。権限は adb で付与してもらう
- 起動の方法を切り替えられるようにしておく（Phase 3 の `VmManager` で、QEMU と AVF を差し替えられる形にする）
- 実機が AVF に対応していなければやらない

## 6. 決めること

| いつ | 何を | 推奨 | 決定（2026-11-05） |
|---|---|---|---|
| Phase 0 の後 | proot を残すか | 計測で Claude Code と `npm install` が実用なら消す。実用でなければ、2.x の間は「速いモード（proot）」としてコンテナごとに選べるようにする | 2.0 で消す（Phase 0 は飛ばした） |
| Phase 0 の後（Phase 4 の前） | Drive の同期をどこで行うか | ゲストの中。作業フォルダが ext4 になり、9p より速く、ハードリンクの問題も無くなる | ゲストの中 |
| Phase 1 | カーネルなどの生成物の置き場所 | GitHub Releases + SHA-256 で固定 | 推奨のとおり |
| Phase 6 の後 | AVF をやるか | 実機が対応していればやる | 実機を確かめてから |

## 7. リスク

| リスク | 影響 | 対策 |
|---|---|---|
| 速さが足りない（Node や Bun は JIT の二重変換で特に重い） | Claude Code などが使いにくい | Phase 0 で計る。`tb-size` を大きく、`-cpu` を選ぶ、複数スレッドの TCG。足りなければ proot を残す |
| Android 用の QEMU のビルドが難しい | Phase 2 が延びる | Termux の patch と手順を参考にする。最後の手段は Termux の .deb を使う（依存が多く重い） |
| 9p が落ちる（Armv9.2） | 端末のストレージが見えない | Drive はゲストの中にする。ストレージは任意の機能にし、だめならファイルの取り込み・書き出しの画面で代える |
| phantom process killer | VM ごと落ちる | 常駐サービスと今の案内（`ChildProcessGuide`）。必要なら JNI で `:vm` プロセスにする |
| Android に強制終了されてディスクが壊れる | データが消える | ext4 のジャーナル、バリアを外さない、起動時の fsck、バックアップ |
| スリープ明けに時計が遅れる | TLS・git・make がおかしくなる | エージェントが端末の時刻に合わせる |
| メモリ不足で Android に殺される | 作業中のものが消える | 既定のメモリを端末に合わせる、balloon、zram |
| 16 KB ページの端末で動かない | 起動できない | すべての実行ファイルを 16 KB 境界でビルドする |
| 電池の減りと発熱 | 使い心地が悪い | 待機時の CPU を計る、vCPU の数の既定を控えめにする、使わないときは止める |
| GPL の義務 | 配布の問題 | 下の 8 章 |

## 8. ライセンス

- 同梱する物: QEMU（GPLv2）、Linux カーネル（GPLv2）、busybox（GPLv2）、e2fsprogs（GPLv2）、glib・libslirp（LGPL・BSD 系）、rclone（MIT）
- 配布するなら、対応するソースを渡せるようにする。ビルド手順・ソースの版と SHA-256・patch をリポジトリに置き、ソースの tar 一式を生成物と同じ GitHub Releases に置く。アプリの「オープンソースのライセンス」に追記する
- Podroid（GPLv2）は設計の参考にする。コードを写す場合は、その部分が GPLv2 になる

## 確認の記録

### エミュレーター（x86_64 の QEMU で arm64 のゲスト。AVD `pocketlinux`、Android 16）

2026-11-06 に確認:

- **VM の起動**: `pocket-agent` が約 1.3 秒（`1327 ms`）で準備完了（`uname -rm` → `6.18.60-pocket aarch64`、`free -m` → 489 MB）
- **コンテナの作成（`ubuntu:24.04`）**: イメージ取得・展開・`user` の作成・`sudo` の導入まで完了
- **基本動作**: `id`（`uid=1001(user)`）、`sudo whoami`（`root`）、ハードリンク（`ln` でリンク数が 2 になる）、`sudo mount -t tmpfs`、DNS（`/etc/resolv.conf` に Android 側の DNS を反映）、`~/drive`・`~/storage` のシンボリックリンク
- **`xdg-open`**: コンテナ内で `xdg-open https://example.com` を実行し、Android 側のブラウザ（Chrome）が開くことを確認
- **Docker**: `apt-get install docker.io iptables` で導入し、`sudo dockerd` が `overlayfs`・`cgroup v2` で起動。`sudo docker run --rm hello-world` と `sudo docker run --rm alpine wget -qO- http://example.com`（コンテナ内からの外部通信）が通ることを確認
- **1.x からの移行**: 1.x の設定・Drive フォルダの移行と、アーキテクチャ不一致（x86_64 の 1.x コンテナを arm64 ゲストに移したとき）のエラー表示（`exec format error`）を確認

2026-11-07 に公開版（2.0.0、versionCode 4）で確認:

- 1.0.0 の上に入れて更新し、VM の起動、`ubuntu:24.04` の作成、`sudo whoami`（`root`）、`uname -rm`（`6.18.60-pocket aarch64`）
- 1.x のコンテナの移行は `exec format error` で失敗し、エラーの表示とコンテナの保持を確認（上と同じ理由。約 7 分）
- Docker と Drive の同期は、このときは試していない

## 参考

- Podroid: [しくみ](https://extv.github.io/Podroid/guide/how-it-works.html)・[速さ](https://extv.github.io/Podroid/guide/performance.html)・[制限](https://extv.github.io/Podroid/guide/limitations.html)・[リポジトリ](https://github.com/ExTV/Podroid)
- AVF の権限の定義: [AOSP packages/modules/Virtualization の AndroidManifest](https://android.googlesource.com/platform/packages/modules/Virtualization/+/750574560/android/android.system.virtualmachine.res/AndroidManifestNext.xml)
- Termux のパッケージ索引（`qemu-system-aarch64-headless` 1:11.1.3。2026-11-05 に確認）
