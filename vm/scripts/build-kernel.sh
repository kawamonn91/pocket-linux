#!/usr/bin/env bash
# VM のカーネル（arm64 の Image）をビルドして vm/out/ に置く。
#
# allnoconfig（全部オフ）を土台に vm/kernel/required.config と extra.config を足す。
# required.config の項目が有効にならなかったら失敗する（依存が足りないときに気づけるように）。
#
# 使い方（Linux。arm64 の機械ならそのまま、x86_64 なら aarch64-linux-gnu-gcc が必要）:
#   vm/scripts/build-kernel.sh
#
# ソース: https://www.kernel.org/ （GPL-2.0）
set -euo pipefail

cd "$(dirname "$0")/../.."
ROOT="$PWD"
VERSION=6.18.60
SHA256=3c2f44ef6acc021a7ad444d9a2e6ec1ef488ff09368793d0004a6080f69d621e
URL="https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-$VERSION.tar.xz"
WORK="${WORK:-$ROOT/vm/build/kernel}"
OUT="${OUT:-$ROOT/vm/out}"
DL="${DL:-$ROOT/vm/build/dl}"

mkdir -p "$WORK" "$OUT" "$DL"
tarball="$DL/linux-$VERSION.tar.xz"
[ -f "$tarball" ] || curl -fsSL "$URL" -o "$tarball"
echo "$SHA256  $tarball" | sha256sum -c --quiet

src="$WORK/linux-$VERSION"
if [ ! -d "$src" ]; then
  tar -C "$WORK" -xf "$tarball"
fi

make_args=(ARCH=arm64 -C "$src" O="$WORK/obj")
if [ "$(uname -m)" != "aarch64" ]; then
  make_args+=(CROSS_COMPILE="${CROSS_COMPILE:-aarch64-linux-gnu-}")
fi
# 誰がいつビルドしても同じ Image になるように
export KBUILD_BUILD_TIMESTAMP="2026-01-01 00:00:00 UTC"
export KBUILD_BUILD_USER=pocket
export KBUILD_BUILD_HOST=pocket-linux
export KBUILD_BUILD_VERSION=1

merged="$WORK/pocket.config"
cat vm/kernel/required.config vm/kernel/extra.config > "$merged"
make "${make_args[@]}" allnoconfig KCONFIG_ALLCONFIG="$merged" >/dev/null

config="$WORK/obj/.config"
missing=0
check() {
  local file="$1" level="$2"
  while IFS= read -r line; do
    case "$line" in ''|'#'*) continue ;; esac
    if ! grep -qxF "$line" "$config"; then
      echo "$level: 有効になっていない: $line（実際: $(grep -E "^(# )?${line%%=*}[= ]" "$config" || echo なし)）" >&2
      [ "$level" = "エラー" ] && missing=1
    fi
  done < "$file"
}
check vm/kernel/required.config エラー
check vm/kernel/extra.config 警告
if [ "$missing" -ne 0 ]; then
  echo "required.config の項目が有効になりませんでした。依存する項目を足してください。" >&2
  exit 1
fi

make "${make_args[@]}" -j"$(nproc)" Image
install -m 644 "$WORK/obj/arch/arm64/boot/Image" "$OUT/Image"
install -m 644 "$config" "$OUT/kernel.config"
ls -l "$OUT/Image"
