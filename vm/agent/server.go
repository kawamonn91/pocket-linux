package main

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// アプリとの制御用のチャンネル（virtio-serial のポート 1 本）の上で、複数のチャンネルを動かす。

// session は 1 つのチャンネル。OPEN を受けた時点で（プロセスを起こす前に）作るので、
// OPEN の直後に届いた DATA や EOF も失わない。
type session struct {
	id     uint32
	pty    bool
	stdin  chan []byte   // nil を送ると標準入力を閉じる
	closed chan struct{} // セッションが終わったら閉じる（stdin は閉じない）
	once   sync.Once

	mu     sync.Mutex
	resize func(cols, rows int)
	signal func(sig int)
	kill   func()
	killed bool // kill が決まる前に CLOSE が届いた
}

func newSession(id uint32, pty bool) *session {
	queue := 64
	if pty {
		queue = 256
	}
	return &session{id: id, pty: pty, stdin: make(chan []byte, queue), closed: make(chan struct{})}
}

func (s *session) finish() {
	s.once.Do(func() { close(s.closed) })
}

// push は標準入力を渡す。読まないプロセスが 1 つあっても全体を止めないよう、
// 端末は待ち行列があふれたら捨てる（文字の入力なので、あふれるのは読み手が止まっているとき）。
// それ以外（tar の流し込みなど）は読まれるまで待つ。これがアプリへの流量の制御になる。
func (s *session) push(b []byte) {
	if s.pty {
		select {
		case s.stdin <- b:
		case <-s.closed:
		default:
		}
		return
	}
	select {
	case s.stdin <- b:
	case <-s.closed:
	}
}

// setControls はプロセスを操作する関数を入れる。すでに CLOSE が届いていたら、すぐ止める。
func (s *session) setControls(resize func(cols, rows int), signal func(sig int), kill func()) {
	s.mu.Lock()
	s.resize, s.signal, s.kill = resize, signal, kill
	killed := s.killed
	s.mu.Unlock()
	if killed && kill != nil {
		kill()
	}
}

func (s *session) doKill() {
	s.mu.Lock()
	s.killed = true
	kill := s.kill
	s.mu.Unlock()
	if kill != nil {
		kill()
	}
}

func (s *session) doSignal(sig int) {
	s.mu.Lock()
	signal := s.signal
	s.mu.Unlock()
	if signal != nil {
		signal(sig)
	}
}

func (s *session) doResize(cols, rows int) {
	s.mu.Lock()
	resize := s.resize
	s.mu.Unlock()
	if resize != nil {
		resize(cols, rows)
	}
}

type server struct {
	out      *frameWriter
	mu       sync.Mutex
	sessions map[uint32]*session
}

func newServer(w io.Writer) *server {
	return &server{out: &frameWriter{w: w}, sessions: map[uint32]*session{}}
}

func (s *server) event(v any) {
	_ = s.out.sendJSON(tEvent, 0, v)
}

func (s *server) exit(ch uint32, ex exitMessage) {
	if sess := s.remove(ch); sess != nil {
		sess.finish()
	}
	_ = s.out.sendJSON(tExit, ch, ex)
}

func (s *server) fail(ch uint32, err error) {
	s.exit(ch, exitMessage{Code: 1, Error: err.Error()})
}

// serve はポートから読み続ける。アプリがいなくなると EOF が続くので、少し待って読み直す。
func (s *server) serve(port *os.File) {
	for {
		f, err := readFrame(port)
		if err != nil {
			time.Sleep(200 * time.Millisecond)
			continue
		}
		s.handle(f)
	}
}

func (s *server) handle(f frame) {
	switch f.typ {
	case tHello:
		var h helloMessage
		_ = json.Unmarshal(f.payload, &h)
		s.resetSessions()
		if h.Time > 0 {
			setClock(time.UnixMilli(h.Time))
		}
		containers.setDNS(h.DNS)
		s.event(map[string]any{"type": "ready", "version": Version, "uptimeMs": uptimeMillis(), "data": dataMounted})
	case tOpen:
		var req openRequest
		if err := json.Unmarshal(f.payload, &req); err != nil {
			s.fail(f.channel, fmt.Errorf("OPEN の中身を読めません: %w", err))
			return
		}
		sess := newSession(f.channel, req.Pty)
		s.mu.Lock()
		s.sessions[f.channel] = sess
		s.mu.Unlock()
		go s.open(sess, req)
	case tData, tEOF:
		sess := s.get(f.channel)
		if sess == nil {
			return
		}
		if f.typ == tEOF {
			sess.push(nil)
			return
		}
		if len(f.payload) > 0 {
			sess.push(f.payload)
		}
	case tResize:
		var r struct{ Cols, Rows int }
		if sess := s.get(f.channel); sess != nil && json.Unmarshal(f.payload, &r) == nil {
			sess.doResize(r.Cols, r.Rows)
		}
	case tSignal:
		var r struct{ Signal int }
		if sess := s.get(f.channel); sess != nil && json.Unmarshal(f.payload, &r) == nil {
			sess.doSignal(r.Signal)
		}
	case tClose:
		if sess := s.remove(f.channel); sess != nil {
			sess.doKill()
			sess.finish()
		}
	}
}

func (s *server) get(ch uint32) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessions[ch]
}

func (s *server) remove(ch uint32) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess := s.sessions[ch]
	delete(s.sessions, ch)
	return sess
}

// resetSessions は、アプリがつなぎ直したときに前のチャンネルを片付ける。
func (s *server) resetSessions() {
	s.mu.Lock()
	old := s.sessions
	s.sessions = map[uint32]*session{}
	s.mu.Unlock()
	for _, sess := range old {
		sess.doKill()
		sess.finish()
	}
}

func (s *server) open(sess *session, req openRequest) {
	ch := sess.id
	switch req.Op {
	case "exec":
		s.exec(sess, req)
	case "untar", "tar", "put", "get", "mkdir", "rm":
		s.helper(sess, req)
	case "stop":
		containers.stop(req.Container)
		s.exit(ch, exitMessage{})
	case "delete":
		s.deleteContainer(ch, req)
	case "df":
		s.df(ch)
	case "du":
		s.du(ch, req)
	case "settime":
		setClock(time.UnixMilli(req.Time))
		s.exit(ch, exitMessage{})
	case "dns":
		containers.setDNS(req.Servers)
		s.exit(ch, exitMessage{})
	case "watch":
		s.watch(sess, req)
	case "proxy":
		s.proxy(sess, req)
	case "shutdown":
		s.exit(ch, exitMessage{})
		shutdown()
	default:
		s.fail(ch, fmt.Errorf("知らない op です: %s", req.Op))
	}
}

// exec はコマンドを動かす。container が空なら VM のルート（rclone などアプリの仕事）、あればそのコンテナの中。
func (s *server) exec(sess *session, req openRequest) {
	ch := sess.id
	sr := spawnRequest{Argv: req.Argv, Env: req.Env, Cwd: req.Cwd, Pty: req.Pty, Cols: req.Cols, Rows: req.Rows}
	if req.Container == "" {
		if len(sr.Env) == 0 {
			sr.Env = rootEnv()
		}
		sp, err := spawnProcess(sr)
		if err != nil {
			s.fail(ch, err)
			return
		}
		done := make(chan exitMessage, 1)
		go func() { done <- exitFromStatus(<-sp.Done) }()
		s.pump(sess, sp.Pid, sp.files(), done, func(pid, sig int) { _ = syscall.Kill(-pid, syscall.Signal(sig)) })
		return
	}
	c, err := containers.get(req.Container, req.Hostname)
	if err != nil {
		s.fail(ch, err)
		return
	}
	rep, done := c.spawn(sr)
	if rep.err != nil {
		s.fail(ch, rep.err)
		return
	}
	s.pump(sess, rep.pid, rep.files, done, c.signal)
}

// pump はプロセスの入出力をチャンネルにつなぐ。files は PTY なら [master]、そうでなければ [stdin, stdout, stderr]。
func (s *server) pump(sess *session, pid int, files []*os.File, done <-chan exitMessage, signal func(pid, sig int)) {
	ch := sess.id
	var stdin, stdout, stderr *os.File
	if len(files) == 1 {
		stdin, stdout = files[0], files[0]
	} else if len(files) == 3 {
		stdin, stdout, stderr = files[0], files[1], files[2]
	} else {
		for _, f := range files {
			f.Close()
		}
		s.fail(ch, fmt.Errorf("受け取った fd の数がおかしい: %d", len(files)))
		return
	}
	isPty := stderr == nil

	var resize func(cols, rows int)
	if isPty {
		resize = func(cols, rows int) { _ = setWinsize(stdout, cols, rows) }
	}
	sess.setControls(resize,
		func(sig int) { signal(pid, sig) },
		func() { signal(pid, int(syscall.SIGKILL)) })

	go func() {
		for {
			var b []byte
			select {
			case b = <-sess.stdin:
			case <-sess.closed:
				return
			}
			if b == nil {
				if !isPty {
					stdin.Close()
				}
				continue
			}
			_, _ = stdin.Write(b)
		}
	}()

	var wg sync.WaitGroup
	var lastOutput atomic.Int64
	var sending atomic.Int32
	lastOutput.Store(time.Now().UnixMilli())
	copyOut := func(r *os.File, typ byte) {
		defer wg.Done()
		buf := make([]byte, 32*1024)
		for {
			n, err := r.Read(buf)
			if n > 0 {
				sending.Add(1)
				serr := s.out.send(typ, ch, buf[:n])
				sending.Add(-1)
				lastOutput.Store(time.Now().UnixMilli())
				if serr != nil {
					return
				}
			}
			if err != nil {
				return // PTY は子がすべて閉じると EIO になる
			}
		}
	}
	wg.Add(1)
	go copyOut(stdout, tData)
	if stderr != nil {
		wg.Add(1)
		go copyOut(stderr, tStderr)
	}

	ex := <-done
	// 出力を出し切るのを待つ。裏で動き続ける子が出力の口を持っていると EOF にならないので、
	// 出力が 1 秒止まったら打ち切る（アプリへの送信で待っている間は数えない）。
	drained := make(chan struct{})
	go func() { wg.Wait(); close(drained) }()
	lastOutput.Store(time.Now().UnixMilli())
	tick := time.NewTicker(100 * time.Millisecond)
	defer tick.Stop()
wait:
	for {
		select {
		case <-drained:
			break wait
		case <-tick.C:
			if sending.Load() == 0 && time.Now().UnixMilli()-lastOutput.Load() > 1000 {
				break wait
			}
		}
	}
	sess.finish()
	for _, f := range files {
		f.Close()
	}
	s.exit(ch, ex)
}

// helper は chroot してファイルを扱う仕事を、別のプロセス（pocket-agent helper）で行う。
func (s *server) helper(sess *session, req openRequest) {
	ch := sess.id
	root := "/"
	if req.Container != "" {
		rootfs, err := rootfsOf(req.Container)
		if err != nil {
			s.fail(ch, err)
			return
		}
		if req.Op == "untar" {
			if err := os.MkdirAll(rootfs, 0o755); err != nil {
				s.fail(ch, err)
				return
			}
		}
		root = rootfs
	}
	p := req.Path
	if p == "" {
		p = "/"
	}
	argv := []string{agentPath, "helper", req.Op, root, p}
	switch req.Op {
	case "put":
		mode := req.Mode
		if mode == 0 {
			mode = 0o644
		}
		argv = append(argv, fmt.Sprintf("%o", mode))
	case "tar":
		argv = append(argv, req.Exclude...)
	}
	sp, err := spawnProcess(spawnRequest{Argv: argv, Env: rootEnv(), Cwd: "/"})
	if err != nil {
		s.fail(ch, err)
		return
	}
	done := make(chan exitMessage, 1)
	go func() { done <- exitFromStatus(<-sp.Done) }()
	s.pump(sess, sp.Pid, sp.files(), done, func(pid, sig int) { _ = syscall.Kill(pid, syscall.Signal(sig)) })
}

func (s *server) deleteContainer(ch uint32, req openRequest) {
	rootfs, err := rootfsOf(req.Container)
	if err != nil {
		s.fail(ch, err)
		return
	}
	containers.stop(req.Container)
	if err := os.RemoveAll(rootfs[:len(rootfs)-len("/rootfs")]); err != nil {
		s.fail(ch, err)
		return
	}
	s.exit(ch, exitMessage{})
}

func rootEnv() []string {
	return []string{
		"PATH=/usr/local/bin:/usr/bin:/sbin:/bin",
		"HOME=/data/system/home",
		"TMPDIR=/tmp",
		"LANG=C.UTF-8",
		"SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt",
		"POCKET_LINUX_VM=1",
	}
}
