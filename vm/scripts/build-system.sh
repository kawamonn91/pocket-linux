#!/usr/bin/env bash
# VM のシステムイメージ（読み取り専用の erofs）とデータ用ディスクのひな形（ext4）を作って vm/out/ に置く。
#
#   system.erofs  … / になる。pocket-agent（PID 1）・rclone・CA 証明書だけ。配布物（Alpine など）は入れない
#   data.img.gz   … 空の ext4（1 GiB）。アプリが疎ファイルとして書き出し、ゲストが必要な大きさまで広げる
#
# 使い方（Linux。Go 1.27、erofs-utils、e2fsprogs が必要）:
#   vm/scripts/build-system.sh
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$PWD"
OUT="${OUT:-$ROOT/vm/out}"
WORK="${WORK:-$ROOT/vm/build/system}"
DL="${DL:-$ROOT/vm/build/dl}"
GO="${GO:-go}"
CACERT_VERSION=2026-10-20
CACERT_SHA256=796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe
# 同じ入力なら同じイメージになるよう、時刻と UUID を固定する
export SOURCE_DATE_EPOCH=1767225600 # 2026-01-01
export E2FSPROGS_FAKE_TIME=$SOURCE_DATE_EPOCH
SYSTEM_UUID=7c0b9a4e-5f0e-4a63-9c4e-706f636b6574
DATA_UUID=2d3e4f50-6172-4a61-9d61-746170636b74

mkdir -p "$OUT" "$WORK" "$DL"
rm -rf "$WORK/root"
root="$WORK/root"
mkdir -p "$root"/{sbin,usr/bin,etc/ssl/certs,dev,proc,sys,run,tmp,data,mnt/storage}

# pocket-agent（静的リンク、arm64）
(cd vm/agent && CGO_ENABLED=0 GOOS=linux GOARCH=arm64 "$GO" build -trimpath -buildvcs=false \
  -ldflags "-s -w" -o "$root/sbin/pocket-agent" .)
install -m 755 "$root/sbin/pocket-agent" "$OUT/pocket-agent"

# rclone（scripts/build-rclone.sh で作った Linux 版をそのまま使う）
install -m 755 vm/rclone-arm64 "$root/usr/bin/rclone"

# CA 証明書（Mozilla の一覧。curl が配っているもの）
ca="$DL/cacert-$CACERT_VERSION.pem"
[ -f "$ca" ] || curl -fsSL "https://curl.se/ca/cacert-$CACERT_VERSION.pem" -o "$ca"
echo "$CACERT_SHA256  $ca" | sha256sum -c --quiet
install -m 644 "$ca" "$root/etc/ssl/certs/ca-certificates.crt"

cat > "$root/etc/os-release" <<'EOF'
NAME="Pocket Linux VM"
ID=pocket-linux-vm
PRETTY_NAME="Pocket Linux VM"
HOME_URL="https://github.com/kawamonn91/pocket-linux"
EOF
printf 'root:x:0:0:root:/data/system/home:/sbin/pocket-agent\n' > "$root/etc/passwd"
printf 'root:x:0:\n' > "$root/etc/group"
printf '127.0.0.1 localhost\n::1 localhost\n' > "$root/etc/hosts"
# DNS はエージェントが /run に書く（アプリから教わる）
ln -s /run/resolv.conf "$root/etc/resolv.conf"

find "$root" -exec touch -h -d "@$SOURCE_DATE_EPOCH" {} +
rm -f "$OUT/system.erofs"
mkfs.erofs -zlz4hc -T"$SOURCE_DATE_EPOCH" --all-root -U "$SYSTEM_UUID" -L pocket-system \
  "$OUT/system.erofs" "$root"

# データ用ディスクのひな形。i ノード表の初期化はゲストで省く（mount の noinit_itable）
data="$WORK/data.img"
rm -f "$data"
truncate -s 1G "$data"
mkfs.ext4 -q -F -L pocket-data -m 0 -U "$DATA_UUID" \
  -E lazy_itable_init=1,lazy_journal_init=1,nodiscard,hash_seed="$DATA_UUID" "$data"
gzip -9n -c "$data" > "$OUT/data.img.gz"

ls -l "$OUT/system.erofs" "$OUT/data.img.gz" "$OUT/pocket-agent"
