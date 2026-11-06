#!/usr/bin/env bash
# Android 用の QEMU（qemu-system-aarch64、TCG だけ）を NDK でビルドして vm/out/<abi>/libqemu.so に置く。
#
# 依存（glib・libslirp・pcre2・libffi・libiconv・zlib）はすべて静的にリンクし、実行ファイル 1 つにする。
# 機器は virt マシンと virtio だけ（vm/qemu/pocket.mak）。
# 同じ入力から同じ物ができるよう、版と SHA-256 を固定する（CI の qemu.yml が確かめる）。
#
# 使い方（Linux の x86_64。NDK、meson、ninja、python3、pkg-config、curl が必要）:
#   ANDROID_NDK_HOME=... vm/scripts/build-qemu.sh arm64-v8a
#   ANDROID_NDK_HOME=... vm/scripts/build-qemu.sh x86_64     # エミュレーター（AVD）での開発用
#
# ソース: https://www.qemu.org/ （GPL-2.0）ほか。ライセンスは docs/vm-plan.md の 8 章
set -euo pipefail

ABI="${1:?使い方: build-qemu.sh <arm64-v8a|x86_64>}"
cd "$(dirname "$0")/../.."
ROOT="$PWD"
OUT="${OUT:-$ROOT/vm/out/$ABI}"
WORK="${WORK:-$ROOT/vm/build/qemu-$ABI}"
DL="${DL:-$ROOT/vm/build/dl}"
API=26 # アプリの minSdk と同じ
NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME を設定してください}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

case "$ABI" in
  arm64-v8a) TRIPLE=aarch64-linux-android; CPU=aarch64; CPU_FAMILY=aarch64 ;;
  x86_64) TRIPLE=x86_64-linux-android; CPU=x86_64; CPU_FAMILY=x86_64 ;;
  *) echo "知らない ABI です: $ABI" >&2; exit 2 ;;
esac

mkdir -p "$DL"
# URL と SHA-256 を固定して取ってくる
fetch() {
  local url="$1" sha="$2" file
  file="$DL/$(basename "$url")"
  [ -f "$file" ] || curl -fsSL --retry 3 "$url" -o "$file"
  echo "$sha  $file" | sha256sum -c --quiet
  echo "$file"
}
QEMU_VERSION=11.1.3
QEMU_TAR=$(fetch "https://download.qemu.org/qemu-$QEMU_VERSION.tar.xz" 5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22)
GLIB_TAR=$(fetch "https://download.gnome.org/sources/glib/2.90/glib-2.90.1.tar.xz" 93c941aa17d5eb1d53fe838365f29a8b4e539c222a256d974ec8f30fc413e396)
SLIRP_TAR=$(fetch "https://gitlab.freedesktop.org/slirp/libslirp/-/archive/v4.9.7/libslirp-v4.9.7.tar.gz" d2567fa06a6357c7755d61c67293b2c0bf069b3f2173bde6b76cf768529a1938)
ICONV_TAR=$(fetch "https://mirrors.kernel.org/gnu/libiconv/libiconv-1.19.tar.gz" 88dd96a8c0464eca144fc791ae60cd31cd8ee78321e67397e25fc095c4a19aa6)
FFI_TAR=$(fetch "https://github.com/libffi/libffi/releases/download/v3.8.0/libffi-3.8.0.tar.gz" 7da3e2d9a171eb0a038f592ecad3ff2bb2550f3496d87b3b29ad0cf4430c0db4)
PCRE2_TAR=$(fetch "https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.49/pcre2-10.49.tar.bz2" 53c156e1ba416a20da8e65395daa132da0d80e76910424caca3fcdae7831d384)
ZLIB_TAR=$(fetch "https://github.com/madler/zlib/releases/download/v1.4/zlib-1.4.tar.xz" 4f11c512053d7bab8d9e9b89bde3575add3e4c50a383b42ef6cb837035fc5a7c)

SYSROOT="$WORK/sysroot"
mkdir -p "$WORK/src" "$SYSROOT" "$OUT"

export PATH="$TOOLCHAIN/bin:$PATH"
export CC="$TOOLCHAIN/bin/$TRIPLE$API-clang"
export CXX="$TOOLCHAIN/bin/$TRIPLE$API-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export NM="$TOOLCHAIN/bin/llvm-nm"
# 同じ入力なら同じ物ができるように（パスを埋め込まない）。16 KB ページの端末でも動くように
export CFLAGS="-O2 -fPIC -ffile-prefix-map=$WORK=. -ffile-prefix-map=$ROOT=. -I$SYSROOT/include"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-L$SYSROOT/lib -Wl,-z,max-page-size=16384 -Wl,--build-id=none"
export SOURCE_DATE_EPOCH=1767225600
export PKG_CONFIG_LIBDIR="$SYSROOT/lib/pkgconfig"
export PKG_CONFIG_PATH=
export PKG_CONFIG_SYSROOT_DIR=
export PKG_CONFIG="$WORK/pkg-config-static"
cat > "$PKG_CONFIG" <<'EOF'
#!/bin/sh
exec pkg-config --static "$@"
EOF
chmod +x "$PKG_CONFIG"

CROSS="$WORK/cross.ini"
cat > "$CROSS" <<EOF
[binaries]
c = '$CC'
cpp = '$CXX'
ar = '$AR'
ranlib = '$RANLIB'
strip = '$STRIP'
nm = '$NM'
pkg-config = '$PKG_CONFIG'

[host_machine]
system = 'android'
kernel = 'linux'
cpu_family = '$CPU_FAMILY'
cpu = '$CPU'
endian = 'little'

[built-in options]
c_args = ['-O2', '-fPIC', '-ffile-prefix-map=$WORK=.', '-I$SYSROOT/include']
c_link_args = ['-L$SYSROOT/lib', '-Wl,-z,max-page-size=16384']
EOF

extract() {
  local tar="$1" dir="$2"
  rm -rf "$WORK/src/$dir"
  mkdir -p "$WORK/src/$dir"
  tar -C "$WORK/src/$dir" --strip-components=1 -xf "$tar"
  echo "$WORK/src/$dir"
}

# 作り直しを省くための印。依存の版やこのスクリプトが変わったら消す
built() { [ -f "$SYSROOT/.built-$1" ]; }
mark() { touch "$SYSROOT/.built-$1"; }

if ! built zlib; then
  src=$(extract "$ZLIB_TAR" zlib)
  (cd "$src" && CHOST=$TRIPLE ./configure --static --prefix="$SYSROOT" >/dev/null && make -j"$(nproc)" install >/dev/null)
  mark zlib
fi

if ! built iconv; then
  src=$(extract "$ICONV_TAR" libiconv)
  (cd "$src" && ./configure --host="$TRIPLE" --prefix="$SYSROOT" --enable-static --disable-shared --disable-nls >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null)
  mark iconv
fi

if ! built ffi; then
  src=$(extract "$FFI_TAR" libffi)
  (cd "$src" && ./configure --host="$TRIPLE" --prefix="$SYSROOT" --enable-static --disable-shared --disable-docs \
    --disable-multi-os-directory --disable-exec-static-tramp >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null)
  mark ffi
fi

if ! built pcre2; then
  src=$(extract "$PCRE2_TAR" pcre2)
  (cd "$src" && ./configure --host="$TRIPLE" --prefix="$SYSROOT" --enable-static --disable-shared --disable-pcre2grep-jit >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null)
  mark pcre2
fi

if ! built glib; then
  src=$(extract "$GLIB_TAR" glib)
  for p in "$ROOT"/vm/qemu/glib-patches/*.patch; do
    [ -e "$p" ] || continue
    patch -d "$src" -p1 --no-backup-if-mismatch < "$p"
  done
  rm -rf "$WORK/build-glib"
  meson setup "$WORK/build-glib" "$src" --cross-file "$CROSS" --prefix "$SYSROOT" --libdir lib \
    --buildtype=release --default-library=static \
    -Dtests=false -Dintrospection=disabled -Dman-pages=disabled -Ddocumentation=false -Dnls=disabled \
    -Dlibmount=disabled -Dselinux=disabled -Dxattr=false -Dsysprof=disabled -Ddtrace=disabled -Dsystemtap=disabled \
    -Dlibelf=disabled -Dglib_debug=disabled -Dglib_assert=false -Dglib_checks=false -Dbsymbolic_functions=false
  ninja -C "$WORK/build-glib" install >/dev/null
  mark glib
fi

if ! built slirp; then
  src=$(extract "$SLIRP_TAR" libslirp)
  rm -rf "$WORK/build-slirp"
  meson setup "$WORK/build-slirp" "$src" --cross-file "$CROSS" --prefix "$SYSROOT" --libdir lib \
    --buildtype=release --default-library=static
  ninja -C "$WORK/build-slirp" install >/dev/null
  mark slirp
fi

# QEMU 本体
src=$(extract "$QEMU_TAR" qemu)
for p in "$ROOT"/vm/qemu/patches/*.patch; do
  patch -d "$src" -p1 --no-backup-if-mismatch < "$p"
done
cp "$ROOT/vm/qemu/pocket.mak" "$src/configs/devices/aarch64-softmmu/pocket.mak"

extra_libs=""
if [ "$ABI" = arm64-v8a ]; then
  # Bionic の setjmp はスタックを切り替える使い方（コルーチン）で落ちるので、自前のものに差し替える
  # （termux-packages#12261。ファイルは Bionic のもの、BSD ライセンス）
  sj="$WORK/setjmp"
  rm -rf "$sj"
  mkdir -p "$sj/private"
  cp "$ROOT"/vm/qemu/setjmp-aarch64/setjmp.S "$sj/"
  cp "$ROOT"/vm/qemu/setjmp-aarch64/bionic_*.h "$sj/private/"
  "$CC" $CFLAGS -I"$sj" -c "$sj/setjmp.S" -o "$sj/setjmp.o"
  "$AR" cru "$SYSROOT/lib/libandroid-setjmp.a" "$sj/setjmp.o"
  extra_libs="-l:libandroid-setjmp.a"
fi

build="$WORK/build-qemu"
rm -rf "$build"
mkdir -p "$build"
cd "$build"
"$src/configure" \
  --prefix=/nonexistent \
  --cross-prefix="$TOOLCHAIN/bin/llvm-" \
  --host-cc=gcc \
  --cc="$CC" \
  --cxx="$CXX" \
  --objcc="$CC" \
  --extra-cflags="$CFLAGS" \
  --extra-ldflags="$LDFLAGS $extra_libs" \
  --target-list=aarch64-softmmu \
  --without-default-features \
  --without-default-devices \
  --with-devices-aarch64=pocket \
  --enable-tcg \
  --enable-slirp \
  --enable-virtfs \
  --enable-attr \
  --enable-fdt=internal \
  --enable-coroutine-pool \
  --enable-trace-backends=nop \
  --disable-docs \
  --disable-tools \
  --disable-guest-agent \
  --disable-werror \
  --disable-stack-protector \
  --disable-debug-tcg \
  --disable-modules \
  --enable-pie
ninja qemu-system-aarch64

"$STRIP" --strip-unneeded -o "$OUT/libqemu.so" "$build/qemu-system-aarch64"
ls -l "$OUT/libqemu.so"
sha256sum "$OUT/libqemu.so"
