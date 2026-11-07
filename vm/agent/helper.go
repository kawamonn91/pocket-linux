package main

import (
	"archive/tar"
	"bufio"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"syscall"
)

// runHelper: pocket-agent helper <op> <root> <path> [引数...]
// root（コンテナの rootfs）に chroot してから作業するので、イメージの中の悪いシンボリックリンクや ..
// があっても root の外には書けない。標準入力・標準出力でデータを受け渡す。
func runHelper(args []string) int {
	if len(args) < 3 {
		fmt.Fprintln(os.Stderr, "使い方: pocket-agent helper <untar|tar|put|get|mkdir|rm|share> <root> <path> ...")
		return 2
	}
	op, root, p := args[0], args[1], path.Clean("/"+args[2])
	if root != "/" {
		if err := syscall.Chroot(root); err != nil {
			fmt.Fprintf(os.Stderr, "chroot %s: %v\n", root, err)
			return 1
		}
	}
	if err := os.Chdir("/"); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	syscall.Umask(0)
	var err error
	switch op {
	case "untar":
		err = untar(bufio.NewReaderSize(os.Stdin, 256*1024), p)
	case "tar":
		w := bufio.NewWriterSize(os.Stdout, 256*1024)
		err = writeTar(w, p, args[3:])
		if ferr := w.Flush(); err == nil {
			err = ferr
		}
	case "put":
		mode := uint64(0o644)
		if len(args) > 3 {
			mode, _ = strconv.ParseUint(args[3], 8, 32)
		}
		err = putFile(p, os.FileMode(mode))
	case "get":
		var f *os.File
		if f, err = os.Open(p); err == nil {
			_, err = io.Copy(os.Stdout, f)
			f.Close()
		}
	case "mkdir":
		err = makeDir(p, args[3:])
	case "share":
		err = shareTree(p)
	case "rm":
		if p == "/" {
			err = fmt.Errorf("/ は消せません")
		} else {
			err = os.RemoveAll(p)
		}
	default:
		err = fmt.Errorf("知らない op です: %s", op)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func putFile(p string, mode os.FileMode) error {
	if err := os.MkdirAll(path.Dir(p), 0o755); err != nil {
		return err
	}
	tmp := p + ".pocket-tmp"
	f, err := os.OpenFile(tmp, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, os.Stdin); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		os.Remove(tmp)
		return err
	}
	_ = os.Chmod(tmp, mode)
	// 上書きするときは、元の所有者を引き継ぐ（/etc/shadow など）
	if st, err := os.Lstat(p); err == nil {
		if sys, ok := st.Sys().(*syscall.Stat_t); ok && st.Mode().IsRegular() {
			_ = os.Lchown(tmp, int(sys.Uid), int(sys.Gid))
		}
	}
	return os.Rename(tmp, p)
}

// untar は OCI（Docker）のレイヤーの tar を dest に展開する。ホワイトアウト（.wh.・.wh..wh..opq）を扱い、
// 所有者・権限・ハードリンク・デバイスファイル・拡張属性をそのまま再現する。
func untar(r io.Reader, dest string) error {
	tr := tar.NewReader(r)
	created := map[string]bool{}
	type dirTime struct {
		path string
		hdr  *tar.Header
	}
	var dirs []dirTime
	count := 0
	for {
		hdr, err := tr.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return fmt.Errorf("tar を読めません（%d 件目）: %w", count+1, err)
		}
		count++
		name := path.Join(dest, path.Clean("/"+hdr.Name))
		dir, base := path.Split(name)
		if base == ".wh..wh..opq" {
			entries, _ := os.ReadDir(dir)
			for _, e := range entries {
				p := path.Join(dir, e.Name())
				if !created[p] {
					_ = os.RemoveAll(p)
				}
			}
			continue
		}
		if strings.HasPrefix(base, ".wh.") {
			// ホワイトアウトが消すのは下のレイヤーのものだけ（同じレイヤーで作ったものは残す）
			p := path.Join(dir, strings.TrimPrefix(base, ".wh."))
			if !created[p] {
				_ = os.RemoveAll(p)
			}
			continue
		}
		if name == dest && hdr.Typeflag == tar.TypeDir {
			dirs = append(dirs, dirTime{name, hdr})
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return err
		}
		if st, err := os.Lstat(name); err == nil {
			if !(st.IsDir() && hdr.Typeflag == tar.TypeDir) {
				if err := os.RemoveAll(name); err != nil {
					return err
				}
			}
		}
		mode := uint32(hdr.Mode & 0o7777)
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.Mkdir(name, 0o755); err != nil && !errors.Is(err, fs.ErrExist) {
				return err
			}
			dirs = append(dirs, dirTime{name, hdr})
		case tar.TypeReg, tar.TypeRegA:
			f, err := os.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
			if err != nil {
				return err
			}
			if _, err := io.Copy(f, tr); err != nil {
				f.Close()
				return err
			}
			if err := f.Close(); err != nil {
				return err
			}
		case tar.TypeSymlink:
			if err := os.Symlink(hdr.Linkname, name); err != nil {
				return err
			}
		case tar.TypeLink:
			target := path.Join(dest, path.Clean("/"+hdr.Linkname))
			if err := os.Link(target, name); err != nil {
				return fmt.Errorf("ハードリンク %s → %s: %w", hdr.Name, hdr.Linkname, err)
			}
			created[name] = true
			continue // 中身と属性はリンク先と同じ
		case tar.TypeChar, tar.TypeBlock, tar.TypeFifo:
			var kind uint32 = syscall.S_IFIFO
			if hdr.Typeflag == tar.TypeChar {
				kind = syscall.S_IFCHR
			} else if hdr.Typeflag == tar.TypeBlock {
				kind = syscall.S_IFBLK
			}
			dev := int((hdr.Devmajor << 8 & 0xfff00) | (hdr.Devminor & 0xff) | (hdr.Devminor << 12 & 0xfff00000))
			if err := syscall.Mknod(name, kind|mode, dev); err != nil {
				return fmt.Errorf("%s: %w", hdr.Name, err)
			}
		default:
			continue // pax のグローバルヘッダーなど
		}
		created[name] = true
		_ = os.Lchown(name, hdr.Uid, hdr.Gid)
		if hdr.Typeflag != tar.TypeSymlink {
			// chown で setuid が落ちるので、権限はその後で付ける
			if err := syscall.Chmod(name, mode); err != nil {
				return err
			}
			for k, v := range hdr.PAXRecords {
				if attr, ok := strings.CutPrefix(k, "SCHILY.xattr."); ok {
					_ = syscall.Setxattr(name, attr, []byte(v), 0)
				}
			}
		}
		if hdr.Typeflag != tar.TypeDir {
			_ = lutimesNano(name, hdr.ModTime.Unix(), int64(hdr.ModTime.Nanosecond()))
		}
	}
	// 中に書くと変わるので、フォルダの属性と時刻は最後に（深いものから）
	sort.SliceStable(dirs, func(i, j int) bool { return len(dirs[i].path) > len(dirs[j].path) })
	for _, d := range dirs {
		_ = os.Lchown(d.path, d.hdr.Uid, d.hdr.Gid)
		_ = syscall.Chmod(d.path, uint32(d.hdr.Mode&0o7777))
		_ = lutimesNano(d.path, d.hdr.ModTime.Unix(), int64(d.hdr.ModTime.Nanosecond()))
	}
	fmt.Fprintf(os.Stderr, "%d 件を展開しました\n", count)
	return nil
}

type zeroReader struct{}

func (zeroReader) Read(b []byte) (int, error) {
	clear(b)
	return len(b), nil
}

// writeTar は src の下を tar にする（バックアップ用）。exclude は src からの相対パスで、
// フォルダならフォルダ自体は残して中身を入れない（/proc や /tmp がコンテナに無いと困るので）。
func writeTar(w io.Writer, src string, exclude []string) error {
	tw := tar.NewWriter(w)
	skip := map[string]bool{}
	for _, e := range exclude {
		skip[path.Clean("/"+e)] = true
	}
	links := map[uint64]string{}
	err := filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel := "/" + strings.TrimPrefix(strings.TrimPrefix(p, src), "/")
		if skip[rel] && !d.IsDir() {
			return nil
		}
		info, err := os.Lstat(p)
		if err != nil {
			return nil
		}
		link := ""
		if info.Mode()&os.ModeSymlink != 0 {
			if link, err = os.Readlink(p); err != nil {
				return nil
			}
		}
		hdr, err := tar.FileInfoHeader(info, link)
		if err != nil {
			return nil
		}
		hdr.Name = "." + rel
		if d.IsDir() && rel != "/" {
			hdr.Name += "/"
		}
		hdr.Uname, hdr.Gname = "", ""
		if st, ok := info.Sys().(*syscall.Stat_t); ok {
			hdr.Uid, hdr.Gid = int(st.Uid), int(st.Gid)
			if info.Mode().IsRegular() && st.Nlink > 1 {
				if first, ok := links[st.Ino]; ok {
					hdr.Typeflag = tar.TypeLink
					hdr.Linkname = first
					hdr.Size = 0
				} else {
					links[st.Ino] = hdr.Name
				}
			}
		}
		// ファイルの権限（ping など）は拡張属性にあるので、バックアップにも残す
		if hdr.Typeflag != tar.TypeSymlink {
			caps := make([]byte, 64)
			if n, err := syscall.Getxattr(p, "security.capability", caps); err == nil && n > 0 {
				hdr.PAXRecords = map[string]string{"SCHILY.xattr.security.capability": string(caps[:n])}
			}
		}
		if err := tw.WriteHeader(hdr); err != nil {
			return err
		}
		if hdr.Typeflag == tar.TypeReg && hdr.Size > 0 {
			// 動いているコンテナも保存するので、途中で読めない・縮んだファイルは 0 で埋める（tar を壊さない）
			var n int64
			if f, err := os.Open(p); err == nil {
				n, _ = io.CopyN(tw, f, hdr.Size)
				f.Close()
			}
			if n < hdr.Size {
				if _, err := io.CopyN(tw, zeroReader{}, hdr.Size-n); err != nil {
					return err
				}
			}
		}
		if skip[rel] {
			return filepath.SkipDir
		}
		return nil
	})
	if err != nil {
		return err
	}
	return tw.Close()
}

// makeDir はフォルダを作る。権限を指定したときは（すでにあるフォルダも）その権限にする
func makeDir(p string, args []string) error {
	if len(args) == 0 {
		syscall.Umask(0o022)
		return os.MkdirAll(p, 0o755)
	}
	mode, err := strconv.ParseUint(args[0], 8, 32)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(p, os.FileMode(mode)); err != nil {
		return err
	}
	return os.Chmod(p, os.FileMode(mode))
}

// shareTree は p の下を、どのコンテナのユーザーからも読み書きできるようにする。
// Drive の同期フォルダは VM の中の rclone（root）とコンテナのユーザーの両方が書くため。
// 実行の権限はそのまま残す。リンクは先のファイルを変えてしまうので触らない。
func shareTree(root string) error {
	return filepath.WalkDir(root, func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		info, err := d.Info()
		if err != nil {
			return err
		}
		var perm os.FileMode
		switch {
		case d.IsDir():
			perm = 0o777
		case info.Mode().IsRegular():
			perm = info.Mode().Perm() | 0o666
		default:
			return nil
		}
		if info.Mode().Perm() == perm {
			return nil
		}
		return os.Chmod(name, perm)
	})
}

// runSharedExec: pocket-agent shared-exec <コマンド> [引数...]
// umask 0 でコマンドに置き換わる。rclone（root）が Drive の同期フォルダに作るファイルを、
// コンテナの一般ユーザーが編集できるようにするため。
func runSharedExec(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "使い方: pocket-agent shared-exec <コマンド> [引数...]")
		return 2
	}
	syscall.Umask(0)
	err := syscall.Exec(args[0], args, os.Environ())
	fmt.Fprintf(os.Stderr, "%s: %v\n", args[0], err)
	return 127
}
