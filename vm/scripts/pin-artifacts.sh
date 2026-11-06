#!/usr/bin/env bash
# GitHub Releases の VM の生成物の版を vm/artifacts.properties に固定する（アプリのビルドが取ってくるもの）。
#
# 使い方（gh が必要）: vm/scripts/pin-artifacts.sh vm-2
set -euo pipefail

TAG="${1:?使い方: pin-artifacts.sh <タグ>}"
cd "$(dirname "$0")/../.."

sums=$(gh release download "$TAG" --pattern SHA256SUMS --output -)
{
  echo "# VM の生成物の版と SHA-256。vm/scripts/pin-artifacts.sh で更新する（手で書き換えない）"
  echo "tag=$TAG"
  for name in Image system.erofs data.img.gz libqemu-arm64-v8a.so libqemu-x86_64.so; do
    sha=$(printf '%s\n' "$sums" | awk -v n="$name" '$2 == n { print $1 }' | tr -d '\r')
    [ -n "$sha" ] || { echo "$name が $TAG にありません" >&2; exit 1; }
    echo "$name=$sha"
  done
} > vm/artifacts.properties
cat vm/artifacts.properties
