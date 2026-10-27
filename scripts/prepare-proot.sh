#!/usr/bin/env bash
# Termux のビルド済み proot をアプリに同梱できる形にして app/src/main/jniLibs/ に置く。
#
# Android 10 以降はアプリの書き込み可能な領域にあるファイルを exec できないため、
# 実行ファイルは lib*.so という名前で APK に入れ、インストール時に展開される
# nativeLibraryDir から直接実行する。
#
# 使い方（Linux か WSL で実行。dpkg-deb と patchelf が必要）:
#   ./scripts/prepare-proot.sh
#
# ソース: https://github.com/termux/proot （GPL-2.0）、talloc（LGPL-3.0）、
#         https://github.com/termux/libandroid-shmem （BSD-3-Clause）
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
WORK="$ROOT/third_party/termux-debs"
OUT="$ROOT/app/src/main/jniLibs"
BASE="https://packages-cf.termux.dev/apt/termux-main"
PATCHELF="${PATCHELF:-patchelf}"

# パッケージのパスと SHA-256（Termux の Packages 索引から 2026-10-27 に取得）
PACKAGES=(
  "pool/main/p/proot/proot_5.1.107.101_aarch64.deb a28355e9be257e1f87f4e9294b3118470d4fd7a5442fed8b278f4443c83407d7"
  "pool/main/p/proot/proot_5.1.107.101_x86_64.deb 85adb8d0d2e1edc6c56e60c17136adf05e54ce75f69407ec7116a4c5997eaed5"
  "pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
  "pool/main/libt/libtalloc/libtalloc_2.5.0_x86_64.deb b8c6d95f20075dc1f9ec6573575b2444e8d526e48e0d8d6d5cf4e071e6e06530"
  "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"
  "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797"
)

mkdir -p "$WORK"
for entry in "${PACKAGES[@]}"; do
  read -r path sha <<<"$entry"
  file="$WORK/$(basename "$path")"
  [ -f "$file" ] || curl -fsSL "$BASE/$path" -o "$file"
  echo "$sha  $file" | sha256sum -c --quiet
done

for pair in "aarch64 arm64-v8a" "x86_64 x86_64"; do
  read -r arch abi <<<"$pair"
  ex="$WORK/x/$arch"
  rm -rf "$ex" && mkdir -p "$ex"
  for deb in "$WORK"/*_"$arch".deb; do dpkg-deb -x "$deb" "$ex"; done
  usr="$ex/data/data/com.termux/files/usr"

  dest="$OUT/$abi"
  mkdir -p "$dest"
  install -m 755 "$usr/bin/proot" "$dest/libproot.so"
  install -m 755 "$usr/libexec/proot/loader" "$dest/libproot-loader.so"
  install -m 755 "$usr/lib/libtalloc.so.2.5.0" "$dest/libtalloc.so"
  install -m 755 "$usr/lib/libandroid-shmem.so" "$dest/libandroid-shmem.so"

  # APK から展開されるのは lib*.so という名前だけなので、依存名もそれに合わせる。
  # 参照先は Termux の固定パスではなく、自分と同じフォルダ（$ORIGIN）にする
  "$PATCHELF" --replace-needed libtalloc.so.2 libtalloc.so "$dest/libproot.so"
  "$PATCHELF" --set-soname libtalloc.so "$dest/libtalloc.so"
  for lib in libproot.so libtalloc.so libandroid-shmem.so; do
    "$PATCHELF" --set-rpath '$ORIGIN' "$dest/$lib"
  done
  echo "$abi: $(ls "$dest" | tr '\n' ' ')"
done
