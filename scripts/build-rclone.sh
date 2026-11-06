#!/usr/bin/env bash
# Google ドライブとの同期に使う rclone を、必要な機能だけ入れてビルドし、vm/rclone-arm64 に置く。
#
# 公式のビルドはすべての機能入りで大きいため、Drive・手元のファイル・双方向同期（bisync）・
# ログイン（authorize）などに絞る（入れる機能は scripts/rclone/main.go）。
# VM の中で動かすので Linux の arm64 版（静的リンク）だけを作る。vm/scripts/build-system.sh が system.erofs に入れる。
#
# 使い方（Linux か WSL で実行。Go 1.26 以降と git が必要）:
#   ./scripts/build-rclone.sh
#   RCLONE_SRC=~/src/rclone ./scripts/build-rclone.sh   # 取得済みのソース（同じ版）を使う
#
# ソース: https://github.com/rclone/rclone （MIT）
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
VERSION=v1.75.2
OUT="$ROOT/vm/rclone-arm64"
GO="${GO:-go}"
SRC="${RCLONE_SRC:-$ROOT/third_party/rclone-build/rclone-$VERSION}"

if [ ! -d "$SRC" ]; then
  git clone -q --depth 1 --branch "$VERSION" https://github.com/rclone/rclone "$SRC"
fi
actual=$(git -C "$SRC" describe --tags --exact-match 2>/dev/null || echo "?")
if [ "$actual" != "$VERSION" ]; then
  echo "rclone のソースが $VERSION ではありません（$actual）: $SRC" >&2
  exit 1
fi

mkdir -p "$SRC/pocketlinux"
cp "$ROOT/scripts/rclone/main.go" "$SRC/pocketlinux/main.go"

cd "$SRC"
# 同じ版の Go なら誰が作っても同じ実行ファイルになるよう、パスと Git の情報は埋め込まない
# （CI の .github/workflows/rclone.yml が作り直して比べる）
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 "$GO" build -trimpath -buildvcs=false \
  -ldflags "-s -w -X github.com/rclone/rclone/fs.Version=$VERSION-pocketlinux" \
  -o "$OUT" ./pocketlinux
echo "$(du -h "$OUT" | cut -f1)"
