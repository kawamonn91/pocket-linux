#!/usr/bin/env bash
# CD（.github/workflows/release.yml）が使う GitHub Secrets を、手元のファイルから登録する。
# 値はコマンドの引数に出さず、標準入力から gh に渡す。
#
# 使い方（リポジトリの直下で）: bash scripts/set-release-secrets.sh
# 要るもの: pocket-linux.jks・keystore.properties google-oauth.properties（どれも Git の対象外）、gh のログイン
set -euo pipefail

repo="${REPO:-kawamonn91/pocket-linux}"
cd "$(dirname "$0")/.."

for f in pocket-linux.jks keystore.properties google-oauth.properties; do
  [ -f "$f" ] || { echo "$f がありません" >&2; exit 1; }
done

prop() { grep "^$2=" "$1" | head -n 1 | cut -d= -f2- | tr -d '\r\n'; }

base64 -w0 pocket-linux.jks | gh secret set RELEASE_KEYSTORE_BASE64 -R "$repo"
prop keystore.properties storePassword | gh secret set RELEASE_KEYSTORE_PASSWORD -R "$repo"
prop keystore.properties keyAlias | gh secret set RELEASE_KEY_ALIAS -R "$repo"
prop keystore.properties keyPassword | gh secret set RELEASE_KEY_PASSWORD -R "$repo"
prop google-oauth.properties clientId | gh secret set GOOGLE_OAUTH_CLIENT_ID -R "$repo"
prop google-oauth.properties clientSecret | gh secret set GOOGLE_OAUTH_CLIENT_SECRET -R "$repo"

gh secret list -R "$repo"
