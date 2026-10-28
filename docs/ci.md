# CI と自動マージ

## CI（`.github/workflows/ci.yml`）

PR（main 向け）と main への push のたびに、GitHub Actions のジョブ `build` が次を行う。

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`（単体テスト・lint・デバッグ版のビルド）
- デバッグ版の APK（`app-debug`）と、テスト・lint のレポート（`reports`）を成果物として 14 日残す

もう1つのワークフロー `.github/workflows/rclone.yml` は、同梱している rclone（`app/src/main/jniLibs/*/librclone.so`）を `scripts/build-rclone.sh` でソースから作り直し、コミットされているものとハッシュが一致するかを確かめる。rclone まわりのファイルが変わったときだけ動き、main の必須チェックではない（一致しなければ PR の画面で失敗が見える）。

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
