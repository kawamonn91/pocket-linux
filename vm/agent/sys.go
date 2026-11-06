package main

import (
	"errors"
	"fmt"
	"os"
	"path"
	"strings"
	"syscall"
	"unsafe"
)

// ioctl の番号（arm64 と x86_64 で同じ asm-generic の値）
const (
	ioctlBLKGETSIZE64 = 0x80081272
	ioctlRTCRDTIME    = 0x80247009
	ioctlEXT4RESIZEFS = 0x40086610
	ioctlFITRIM       = 0xc0185879
	atSymlinkNoFollow = 0x100
)

func ioctl(fd uintptr, req uintptr, arg uintptr) error {
	_, _, e := syscall.Syscall(syscall.SYS_IOCTL, fd, req, arg)
	if e != 0 {
		return e
	}
	return nil
}

type winsize struct {
	Rows, Cols, X, Y uint16
}

func setWinsize(f *os.File, cols, rows int) error {
	if cols <= 0 || rows <= 0 {
		return nil
	}
	ws := winsize{Rows: uint16(rows), Cols: uint16(cols)}
	raw, err := f.SyscallConn()
	if err != nil {
		return err
	}
	var ierr error
	if err := raw.Control(func(fd uintptr) {
		ierr = ioctl(fd, syscall.TIOCSWINSZ, uintptr(unsafe.Pointer(&ws)))
	}); err != nil {
		return err
	}
	return ierr
}

// openPty は /dev/ptmx から PTY の組を作る（呼んだプロセスの devpts で）。
func openPty() (master, slave *os.File, err error) {
	master, err = os.OpenFile("/dev/ptmx", os.O_RDWR|syscall.O_NOCTTY|syscall.O_CLOEXEC, 0)
	if err != nil {
		return nil, nil, err
	}
	raw, err := master.SyscallConn()
	if err != nil {
		master.Close()
		return nil, nil, err
	}
	var n uint32
	var ierr error
	_ = raw.Control(func(fd uintptr) {
		var unlock int32
		if ierr = ioctl(fd, syscall.TIOCSPTLCK, uintptr(unsafe.Pointer(&unlock))); ierr != nil {
			return
		}
		ierr = ioctl(fd, syscall.TIOCGPTN, uintptr(unsafe.Pointer(&n)))
	})
	if ierr != nil {
		master.Close()
		return nil, nil, ierr
	}
	slave, err = os.OpenFile(fmt.Sprintf("/dev/pts/%d", n), os.O_RDWR|syscall.O_NOCTTY|syscall.O_CLOEXEC, 0)
	if err != nil {
		master.Close()
		return nil, nil, err
	}
	return master, slave, nil
}

func lutimesNano(p string, sec, nsec int64) error {
	ts := [2]syscall.Timespec{{Sec: sec, Nsec: nsec}, {Sec: sec, Nsec: nsec}}
	b, err := syscall.BytePtrFromString(p)
	if err != nil {
		return err
	}
	_, _, e := syscall.Syscall6(syscall.SYS_UTIMENSAT, uintptr(atFdCwdU), uintptr(unsafe.Pointer(b)),
		uintptr(unsafe.Pointer(&ts[0])), atSymlinkNoFollow, 0, 0)
	if e != 0 {
		return e
	}
	return nil
}

var atFdCwdU = func() uintptr { v := -100; return uintptr(v) }()

// lookPath は env の PATH から実行ファイルを探す（呼んだプロセスのルートで）。
func lookPath(file string, env []string) (string, error) {
	if strings.Contains(file, "/") {
		return file, nil
	}
	pathEnv := "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
	for _, kv := range env {
		if v, ok := strings.CutPrefix(kv, "PATH="); ok {
			pathEnv = v
		}
	}
	for _, dir := range strings.Split(pathEnv, ":") {
		if dir == "" {
			continue
		}
		p := path.Join(dir, file)
		if st, err := os.Stat(p); err == nil && st.Mode().IsRegular() && st.Mode()&0o111 != 0 {
			return p, nil
		}
	}
	return "", fmt.Errorf("%s が見つかりません", file)
}

// secureJoin は root の中のパス p を、root の外に出ないように解決する（シンボリックリンクも root の中でたどる）。
// pivot_root の前にコンテナの rootfs の中へ mount や書き込みをするときに使う。
func secureJoin(root, p string) (string, error) {
	var resolved string // root からの相対（先頭の / なし）
	rest := strings.Split(strings.TrimPrefix(path.Clean("/"+p), "/"), "/")
	hops := 0
	for len(rest) > 0 {
		part := rest[0]
		rest = rest[1:]
		switch part {
		case "", ".":
			continue
		case "..":
			resolved = path.Dir("/" + resolved)[1:]
			continue
		}
		next := path.Join(resolved, part)
		st, err := os.Lstat(path.Join(root, next))
		if err != nil {
			if errors.Is(err, os.ErrNotExist) {
				// まだ無いところから先は、そのままつなげる
				resolved = path.Join(append([]string{next}, rest...)...)
				rest = nil
				break
			}
			return "", err
		}
		if st.Mode()&os.ModeSymlink == 0 {
			resolved = next
			continue
		}
		hops++
		if hops > 40 {
			return "", fmt.Errorf("%s: シンボリックリンクが多すぎます", p)
		}
		target, err := os.Readlink(path.Join(root, next))
		if err != nil {
			return "", err
		}
		if strings.HasPrefix(target, "/") {
			resolved = ""
		}
		rest = append(strings.Split(target, "/"), rest...)
	}
	return path.Join(root, resolved), nil
}

func writeFileIn(root, p string, data []byte, mode os.FileMode) error {
	full, err := secureJoin(root, p)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(path.Dir(full), 0o755); err != nil {
		return err
	}
	if st, err := os.Lstat(full); err == nil && st.Mode()&os.ModeSymlink != 0 {
		_ = os.Remove(full)
	}
	return os.WriteFile(full, data, mode)
}
