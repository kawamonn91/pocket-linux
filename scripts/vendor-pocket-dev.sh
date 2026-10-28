#!/usr/bin/env bash
# IDE の窓口で動かす pocket-dev（サーバーと画面）を app/src/main/assets/pocket-dev/ に取り込む。
# アプリは起動時にこれを files/pocket-dev に展開し、コンテナの /opt/pocket-dev として見せる（ide/IdeServer.kt）。
#
# 使い方（pocket-dev を隣のフォルダに clone してある前提。別の場所なら引数で渡す）:
#   ./scripts/vendor-pocket-dev.sh [../pocket-dev]
#
# ソース: https://github.com/kawamonn91/pocket-dev
set -euo pipefail

cd "$(dirname "$0")/.."
SRC="${1:-../pocket-dev}"
OUT="app/src/main/assets/pocket-dev"

commit=$(git -C "$SRC" rev-parse HEAD)
if [ -n "$(git -C "$SRC" status --porcelain -- agent web)" ]; then
  echo "pocket-dev の agent/ か web/ にコミットしていない変更があります: $SRC" >&2
  exit 1
fi

rm -rf "$OUT"
mkdir -p "$OUT/agent" "$OUT/web"
cp "$SRC"/agent/*.py "$OUT/agent/"
cp "$SRC"/web/* "$OUT/web/"
# アプリは VERSION が変わったときだけ展開し直す
echo "$commit" > "$OUT/VERSION"
echo "pocket-dev $commit を取り込みました: $(find "$OUT" -type f | wc -l) ファイル"
