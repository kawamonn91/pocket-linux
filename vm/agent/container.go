package main

import (
	"fmt"
	"os"
	"path"
	"regexp"
	"strings"
	"sync"
	"syscall"
	"time"
)

// エージェントの側から見たコンテナ（動いている cinit 1 つ）。

const (
	dataDir       = "/data"
	containersDir = "/data/containers"
	agentPath     = "/sbin/pocket-agent"
)

var validID = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)

func rootfsOf(id string) (string, error) {
	if !validID.MatchString(id) {
		return "", fmt.Errorf("コンテナの ID が正しくありません: %q", id)
	}
	return path.Join(containersDir, id, "rootfs"), nil
}

type container struct {
	id   string
	pid  int
	ctl  *os.File
	done chan struct{} // cinit が終わったら閉じる

	sendMu  sync.Mutex
	mu      sync.Mutex
	nextID  int
	pending map[int]chan spawnReply
	exits   map[int]chan exitMessage
}

type spawnReply struct {
	pid   int
	files []*os.File
	err   error
}

type containerTable struct {
	mu   sync.Mutex
	byID map[string]*container
	dns  []string
}

var containers = &containerTable{byID: map[string]*container{}}

func (t *containerTable) setDNS(servers []string) {
	// VM のルートの /etc/resolv.conf（rclone が読む）は /run/resolv.conf へのリンク
	if err := writeResolvConf("/run", "/resolv.conf", servers); err != nil {
		logf("resolv.conf を書けません: %v", err)
	}
	t.mu.Lock()
	t.dns = append([]string(nil), servers...)
	list := make([]*container, 0, len(t.byID))
	for _, c := range t.byID {
		list = append(list, c)
	}
	t.mu.Unlock()
	for _, c := range list {
		_ = c.send(ctlMessage{Type: "resolv", Servers: servers})
	}
}

// get は動いているコンテナを返す。動いていなければ起動する。
func (t *containerTable) get(id, hostname string) (*container, error) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if c, ok := t.byID[id]; ok {
		select {
		case <-c.done:
			delete(t.byID, id)
		default:
			return c, nil
		}
	}
	c, err := startContainer(id, hostname, t.dns)
	if err != nil {
		return nil, err
	}
	t.byID[id] = c
	go func() {
		<-c.done
		t.mu.Lock()
		if t.byID[id] == c {
			delete(t.byID, id)
		}
		t.mu.Unlock()
	}()
	return c, nil
}

func (t *containerTable) stop(id string) {
	t.mu.Lock()
	c := t.byID[id]
	delete(t.byID, id)
	t.mu.Unlock()
	if c != nil {
		c.kill()
		select {
		case <-c.done:
		case <-time.After(5 * time.Second):
			logf("コンテナ %s が止まりません", id)
		}
	}
}

func (t *containerTable) stopAll() {
	t.mu.Lock()
	list := make([]*container, 0, len(t.byID))
	for id, c := range t.byID {
		list = append(list, c)
		delete(t.byID, id)
	}
	t.mu.Unlock()
	for _, c := range list {
		c.kill()
	}
	for _, c := range list {
		select {
		case <-c.done:
		case <-time.After(5 * time.Second):
		}
	}
}

func startContainer(id, hostname string, dns []string) (*container, error) {
	rootfs, err := rootfsOf(id)
	if err != nil {
		return nil, err
	}
	if st, err := os.Stat(rootfs); err != nil || !st.IsDir() {
		return nil, fmt.Errorf("コンテナ %s の中身がありません", id)
	}
	fds, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_SEQPACKET|syscall.SOCK_CLOEXEC, 0)
	if err != nil {
		return nil, err
	}
	parent := os.NewFile(uintptr(fds[0]), "cinit-ctl")
	child := os.NewFile(uintptr(fds[1]), "cinit-ctl-child")
	defer child.Close()

	if hostname == "" {
		hostname = id
	}
	storage := "0"
	if storageMounted() {
		storage = "1"
	}
	env := []string{
		"POCKET_ROOTFS=" + rootfs,
		"POCKET_HOSTNAME=" + sanitizeHostname(hostname),
		"POCKET_DNS=" + strings.Join(dns, ","),
		"POCKET_STORAGE=" + storage,
		"PATH=/usr/bin:/sbin",
	}
	devnull, err := os.Open("/dev/null")
	if err != nil {
		parent.Close()
		return nil, err
	}
	defer devnull.Close()
	attr := &syscall.ProcAttr{
		Env:   env,
		Files: []uintptr{devnull.Fd(), 2, 2, child.Fd()},
		Sys: &syscall.SysProcAttr{
			Cloneflags: syscall.CLONE_NEWNS | syscall.CLONE_NEWPID | syscall.CLONE_NEWUTS | syscall.CLONE_NEWIPC,
		},
	}
	pid, wait, err := forkExec(agentPath, []string{"pocket-agent", "cinit"}, attr)
	if err != nil {
		parent.Close()
		return nil, fmt.Errorf("コンテナを起動できません: %w", err)
	}
	c := &container{
		id:      id,
		pid:     pid,
		ctl:     parent,
		done:    make(chan struct{}),
		pending: map[int]chan spawnReply{},
		exits:   map[int]chan exitMessage{},
	}
	go func() {
		<-wait
		c.finish()
	}()

	type first struct {
		m   ctlMessage
		err error
	}
	ready := make(chan first, 1)
	go func() {
		m, _, err := recvCtl(parent)
		ready <- first{m, err}
	}()
	select {
	case r := <-ready:
		if r.err != nil {
			c.kill()
			return nil, fmt.Errorf("コンテナが応答しません: %w", r.err)
		}
		if r.m.Type != "ready" {
			c.kill()
			return nil, fmt.Errorf("コンテナを準備できません: %s", r.m.Error)
		}
	case <-time.After(30 * time.Second):
		c.kill()
		return nil, fmt.Errorf("コンテナの準備が終わりません")
	}
	go c.readLoop()
	return c, nil
}

func sanitizeHostname(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		switch {
		case r >= 'a' && r <= 'z', r >= '0' && r <= '9', r == '-':
			b.WriteRune(r)
		default:
			b.WriteRune('-')
		}
	}
	h := strings.Trim(b.String(), "-")
	if len(h) > 63 {
		h = h[:63]
	}
	if h == "" {
		h = "pocket"
	}
	return h
}

func (c *container) send(m ctlMessage, files ...*os.File) error {
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	return sendCtl(c.ctl, m, files...)
}

func (c *container) readLoop() {
	for {
		m, files, err := recvCtl(c.ctl)
		if err != nil {
			c.kill()
			return
		}
		c.mu.Lock()
		switch m.Type {
		case "spawned":
			if ch, ok := c.pending[m.ID]; ok {
				delete(c.pending, m.ID)
				if m.Error != "" {
					ch <- spawnReply{err: fmt.Errorf("%s", m.Error)}
				} else {
					ch <- spawnReply{pid: m.Pid, files: files}
				}
				files = nil
			}
		case "exit":
			if ch, ok := c.exits[m.ID]; ok {
				delete(c.exits, m.ID)
				ch <- exitMessage{Code: m.Code, Signal: m.Signal}
			}
		}
		c.mu.Unlock()
		for _, f := range files {
			f.Close()
		}
	}
}

// spawn はコンテナの中でプロセスを起こす。返す exit は、プロセスが終わったときに届く。
func (c *container) spawn(r spawnRequest) (spawnReply, <-chan exitMessage) {
	reply := make(chan spawnReply, 1)
	exit := make(chan exitMessage, 1)
	c.mu.Lock()
	c.nextID++
	r.ID = c.nextID
	c.pending[r.ID] = reply
	c.exits[r.ID] = exit
	c.mu.Unlock()
	if err := c.send(ctlMessage{Type: "spawn", Req: &r}); err != nil {
		c.mu.Lock()
		delete(c.pending, r.ID)
		delete(c.exits, r.ID)
		c.mu.Unlock()
		return spawnReply{err: err}, nil
	}
	select {
	case rep := <-reply:
		if rep.err != nil {
			c.mu.Lock()
			delete(c.exits, r.ID)
			c.mu.Unlock()
			return rep, nil
		}
		return rep, exit
	case <-c.done:
		return spawnReply{err: fmt.Errorf("コンテナが止まりました")}, nil
	}
}

func (c *container) signal(pid, sig int) {
	_ = c.send(ctlMessage{Type: "signal", Pid: pid, Signal: sig})
}

func (c *container) kill() {
	_ = syscall.Kill(c.pid, syscall.SIGKILL)
}

func (c *container) finish() {
	c.mu.Lock()
	defer c.mu.Unlock()
	select {
	case <-c.done:
		return
	default:
	}
	close(c.done)
	c.ctl.Close()
	for id, ch := range c.pending {
		ch <- spawnReply{err: fmt.Errorf("コンテナが止まりました")}
		delete(c.pending, id)
	}
	// 名前空間の PID 1 が終わると中のプロセスもすべて終わる
	for id, ch := range c.exits {
		ch <- exitMessage{Code: 137, Signal: int(syscall.SIGKILL)}
		delete(c.exits, id)
	}
}
