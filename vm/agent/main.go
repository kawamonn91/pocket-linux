// pocket-agent: Pocket Linux の VM の中で PID 1 として動くプログラム。
//
// VM の起動（mount・ディスク・ネットワーク）、アプリとの制御用のチャンネル（virtio-serial）、
// コンテナ（名前空間 + pivot_root）の実行を受け持つ。約束事は docs/vm-protocol.md。
//
// 同じ実行ファイルを、次の役目でも使う:
//
//	pocket-agent              PID 1（VM の init）
//	pocket-agent cinit        コンテナの中の PID 1（エージェントが名前空間を作って起動する）
//	pocket-agent helper ...   コンテナの rootfs に chroot して tar の展開などをする
package main

import (
	"fmt"
	"os"
)

// Version はエージェントの版。アプリは READY の version で、ゲストとの組み合わせを確かめる。
const Version = "1"

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "cinit":
			runCinit()
			return
		case "helper":
			os.Exit(runHelper(os.Args[2:]))
		case "version":
			fmt.Println(Version)
			return
		}
	}
	if os.Getpid() != 1 {
		fmt.Fprintln(os.Stderr, "pocket-agent: VM の init（PID 1）として起動してください")
		os.Exit(2)
	}
	runInit()
}
