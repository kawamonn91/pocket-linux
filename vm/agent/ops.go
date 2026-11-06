package main

import (
	"encoding/json"
	"io"
	"io/fs"
	"net"
	"os"
	"path"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

func (s *server) df(ch uint32) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dataDir, &st); err != nil {
		s.fail(ch, err)
		return
	}
	bs := uint64(st.Bsize)
	b, _ := json.Marshal(map[string]uint64{
		"total": st.Blocks * bs,
		"free":  st.Bavail * bs,
		"used":  (st.Blocks - st.Bfree) * bs,
	})
	_ = s.out.send(tData, ch, b)
	s.exit(ch, exitMessage{})
}

// du はコンテナが使っているディスクの量（実際に使っているブロック。ハードリンクは 1 回だけ数える）。
func (s *server) du(ch uint32, req openRequest) {
	rootfs, err := rootfsOf(req.Container)
	if err != nil {
		s.fail(ch, err)
		return
	}
	var total uint64
	seen := map[uint64]bool{}
	_ = filepath.WalkDir(path.Dir(rootfs), func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		st, ok := info.Sys().(*syscall.Stat_t)
		if !ok {
			return nil
		}
		if st.Nlink > 1 && !d.IsDir() {
			if seen[st.Ino] {
				return nil
			}
			seen[st.Ino] = true
		}
		total += uint64(st.Blocks) * 512
		return nil
	})
	b, _ := json.Marshal(map[string]uint64{"bytes": total})
	_ = s.out.send(tData, ch, b)
	s.exit(ch, exitMessage{})
}

// watch はフォルダの下の変化を見張り、変わるたびに 1 行（変わったパス）を送る（Drive の同期のきっかけ）。
// アプリが CLOSE するまで続く。
func (s *server) watch(sess *session, req openRequest) {
	ch := sess.id
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		s.fail(ch, err)
		return
	}
	f := os.NewFile(uintptr(fd), "inotify")
	exclude := map[string]bool{}
	for _, e := range req.Exclude {
		exclude[e] = true
	}
	const mask = syscall.IN_CREATE | syscall.IN_DELETE | syscall.IN_MODIFY | syscall.IN_CLOSE_WRITE |
		syscall.IN_MOVED_FROM | syscall.IN_MOVED_TO | syscall.IN_DELETE_SELF | syscall.IN_ATTRIB
	var mu sync.Mutex
	dirs := map[int32]string{}
	var add func(dir string)
	add = func(dir string) {
		_ = filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
			if err != nil || !d.IsDir() {
				return nil
			}
			if p != dir && exclude[d.Name()] {
				return filepath.SkipDir
			}
			wd, err := syscall.InotifyAddWatch(fd, p, mask)
			if err == nil {
				mu.Lock()
				dirs[int32(wd)] = p
				mu.Unlock()
			}
			return nil
		})
	}
	add(req.Path)

	closed := make(chan struct{})
	var closeOnce sync.Once
	sess.setControls(nil, nil, func() {
		closeOnce.Do(func() {
			close(closed)
			f.Close()
		})
	})
	buf := make([]byte, 64*1024)
	for {
		n, err := f.Read(buf)
		if err != nil || n <= 0 {
			break
		}
		var lines []string
		for off := 0; off+syscall.SizeofInotifyEvent <= n; {
			ev := (*syscall.InotifyEvent)(unsafe.Pointer(&buf[off]))
			nameBytes := buf[off+syscall.SizeofInotifyEvent : off+syscall.SizeofInotifyEvent+int(ev.Len)]
			name := strings.TrimRight(string(nameBytes), "\x00")
			off += syscall.SizeofInotifyEvent + int(ev.Len)
			mu.Lock()
			dir := dirs[ev.Wd]
			mu.Unlock()
			if dir == "" || exclude[name] {
				continue
			}
			p := path.Join(dir, name)
			if ev.Mask&syscall.IN_ISDIR != 0 && ev.Mask&(syscall.IN_CREATE|syscall.IN_MOVED_TO) != 0 {
				add(p)
			}
			lines = append(lines, strings.TrimPrefix(p, req.Path))
		}
		if len(lines) > 0 {
			if s.out.send(tData, ch, []byte(strings.Join(lines, "\n")+"\n")) != nil {
				break
			}
		}
	}
	select {
	case <-closed:
		// アプリが閉じた
		s.exit(ch, exitMessage{})
	default:
		f.Close()
		s.exit(ch, exitMessage{Code: 1, Error: "見張りが止まりました"})
	}
}

// proxy は VM の中の TCP をつなぐ（rclone のログインのときに 10.0.2.15:53682 → 127.0.0.1:53682）。
// アプリが CLOSE するまで続く。
func (s *server) proxy(sess *session, req openRequest) {
	ch := sess.id
	ln, err := net.Listen("tcp", req.Listen)
	if err != nil {
		s.fail(ch, err)
		return
	}
	sess.setControls(nil, nil, func() { ln.Close() })
	for {
		conn, err := ln.Accept()
		if err != nil {
			break
		}
		go func(a net.Conn) {
			defer a.Close()
			b, err := net.DialTimeout("tcp", req.Target, 5*time.Second)
			if err != nil {
				return
			}
			defer b.Close()
			go func() { _, _ = io.Copy(b, a); b.(*net.TCPConn).CloseWrite() }()
			_, _ = io.Copy(a, b)
		}(conn)
	}
	s.exit(ch, exitMessage{})
}

// watchOpenRequests はコンテナの pocket-open（xdg-open）が /run/pocket-linux/open に置いた *.url を、
// アプリに「URL を開いて」と伝える。
func watchOpenRequests(srv *server) {
	const dir = "/run/pocket/open"
	fd, err := syscall.InotifyInit1(syscall.IN_CLOEXEC | syscall.IN_NONBLOCK)
	if err != nil {
		return
	}
	if _, err := syscall.InotifyAddWatch(fd, dir, syscall.IN_CLOSE_WRITE|syscall.IN_MOVED_TO); err != nil {
		return
	}
	f := os.NewFile(uintptr(fd), "inotify-open")
	buf := make([]byte, 16*1024)
	for {
		n, err := f.Read(buf)
		if err != nil {
			return
		}
		for off := 0; off+syscall.SizeofInotifyEvent <= n; {
			ev := (*syscall.InotifyEvent)(unsafe.Pointer(&buf[off]))
			name := strings.TrimRight(string(buf[off+syscall.SizeofInotifyEvent:off+syscall.SizeofInotifyEvent+int(ev.Len)]), "\x00")
			off += syscall.SizeofInotifyEvent + int(ev.Len)
			if !strings.HasSuffix(name, ".url") {
				continue
			}
			p := path.Join(dir, name)
			b, err := os.ReadFile(p)
			_ = os.Remove(p)
			if err != nil {
				continue
			}
			url := strings.TrimSpace(strings.SplitN(string(b), "\n", 2)[0])
			if url != "" {
				srv.event(map[string]any{"type": "open-url", "url": url})
			}
		}
	}
}
