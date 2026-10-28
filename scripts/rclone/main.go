// Pocket Linux に同梱する rclone の入口。Google ドライブとの同期に使う機能だけを読み込む
// （公式の rclone.go は backend/all と cmd/all ですべての機能を読み込み、1 つの CPU 向けで約 80MB になる）。
// scripts/build-rclone.sh が rclone のソースの中にコピーしてビルドする。
package main

import (
	_ "github.com/rclone/rclone/backend/drive"
	_ "github.com/rclone/rclone/backend/local"
	"github.com/rclone/rclone/cmd"
	_ "github.com/rclone/rclone/cmd/authorize"
	_ "github.com/rclone/rclone/cmd/bisync"
	_ "github.com/rclone/rclone/cmd/lsjson"
	_ "github.com/rclone/rclone/cmd/mkdir"
	_ "github.com/rclone/rclone/cmd/version"
)

func main() {
	cmd.Main()
}
