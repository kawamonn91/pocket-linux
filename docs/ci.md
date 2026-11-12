# CI と自動マージ

## CI（`.github/workflows/ci.yml`）

PR（main 向け）と main への push のたびに、GitHub Actions のジョブ `build` が次を行う。

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`（単体テスト・lint・デバッグ版のビルド）
- デバッグ版の APK（`app-debug`）と、テスト・lint のレポート（`reports`）を成果物として 14 日残す

`.github/workflows/rclone.yml` は、VM に入れる rclone（`vm/rclone-arm64`）を `scripts/build-rclone.sh` でソースから作り直し、コミットされているものとハッシュが一致するかを確かめる。rclone まわりのファイルが変わったときだけ動き、main の必須チェックではない（一致しなければ PR の画面で失敗が見える）。

## VM の生成物

- `guest.yml`: カーネル・`system.erofs`・エージェントをビルドし、QEMU で起動してコンテナの中の sudo までを確かめる（`vm/test/boot_test.py`）。`vm/` を変えた PR と main への push で動く。必須チェックではない
- `qemu.yml`: Android 用の QEMU を NDK でビルドする。QEMU まわりを変えた PR と main への push で動く。必須チェックではない
- `vm-release.yml`（手動）: 上の 2 つを動かし、起動テストが通ったら GitHub Releases に置く。Linux・QEMU などのソースの tar も同じ場所に置く（GPL の義務）

アプリのビルドは `vm/artifacts.properties` の版と SHA-256 で Releases から取ってきて APK に入れる（`app/build.gradle.kts` の `PrepareVmArtifacts`）。ゲストや QEMU を変えたら、次の手順で更新する。

```bash
gh workflow run vm-release.yml --ref <ブランチ> -f tag=vm-2   # 終わるまで 10 分ほど
vm/scripts/pin-artifacts.sh vm-2                             # vm/artifacts.properties を書き換える
```

JDK は手元と同じ Temurin 25。足りない SDK・NDK は AGP が取得する。アクションはコミットの SHA で固定している（更新するときは SHA ごと書き換える）。

## main の保護と自動マージ

- main は `build` が通らないとマージできない（ブランチの保護。管理者は対象外にしてあり、緊急時は直接 push できる）
- リポジトリで自動マージを有効にし、マージしたブランチは自動で消える
- PR を作ったら、自動マージを予約する。`build` が通った時点でマージされ、失敗したらマージされない

```bash
gh pr create --base main --title "…" --body "…"
gh pr merge --auto --merge   # build が通ったらマージする（マージコミット）
```

CI が失敗したら、同じブランチに直して push すれば、もう一度 CI が走り、通ればそのままマージされる。

## CD（`.github/workflows/release.yml`）

`v` で始まるタグ（`v1.2.3` など）を push すると、ジョブ `release` が次を行う。

1. タグが `app/build.gradle.kts` の `versionName` と合っているか確かめる（`v` + versionName）
2. GitHub Secrets から署名鍵と設定を書き出す
3. 単体テストとリリース版のビルド
4. `apksigner` で署名を確かめる。証明書の SHA-256 がストアに出している版と同じか（ワークフローの `EXPECTED_CERT_SHA256`）も見る
5. `pocket-linux-<版>.apk` と `SHA256SUMS` を GitHub Releases に置く

手で動かす（`gh workflow run release.yml`）と、4 までを行ってリリースは作らない（CD の動作確認用）。

### GitHub Secrets

| 名前 | 中身 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `pocket-linux.jks` を base64 にしたもの |
| `RELEASE_KEYSTORE_PASSWORD` | `keystore.properties` の `storePassword` |
| `RELEASE_KEY_ALIAS` | `keyAlias` |
| `RELEASE_KEY_PASSWORD` | `keyPassword` |
| `GOOGLE_OAUTH_CLIENT_ID` | `google-oauth.properties` の `clientId`（Drive のログイン） |
| `GOOGLE_OAUTH_CLIENT_SECRET` | `clientSecret` |

登録（し直し）は `bash scripts/set-release-secrets.sh`。手元のファイルから読み、値をコマンドの引数に出さずに標準入力から `gh secret set` に渡す。

### 新しい版を出す

1. 作業ブランチで `app/build.gradle.kts` の `versionCode` を 1 つ上げ、`versionName` を変える（T-tech Store は同じ versionCode を受け付けない）
2. PR を作り、CI が通ったら main に入れる
3. main でタグを付けて push し、`release` が終わるのを待つ

   ```bash
   git switch main && git pull --ff-only
   git tag v1.2.3 && git push origin v1.2.3
   gh run watch "$(gh run list --workflow release.yml --limit 1 --json databaseId -q '.[0].databaseId')"
   ```

4. Releases の APK を手元に取り、appstore の `publish-app.mjs` で T-tech Store に出す（本番の鍵は raspi1 から環境変数にだけ読む。`~/.claude/notes/infra.md` の「T-tech Store のスクリプトを本番に向けて動かす」）

   ```bash
   gh release download v1.2.3 -R kawamonn91/pocket-linux -p '*.apk' -D "$SP"
   cd /c/dev/appstore/web
   while IFS='=' read -r k v; do [ -n "$k" ] && export "$k=$v"; done < <(ssh -o BatchMode=yes raspi1 'grep -E "^(NEXT_PUBLIC_SUPABASE_URL|SUPABASE_SERVICE_ROLE_KEY|R2_[A-Z_]+)=" /home/pi/.config/ttech-store/env')
   node scripts/publish-app.mjs --apk "$SP/pocket-linux-1.2.3.apk" --notes "…" --publish
   ```
