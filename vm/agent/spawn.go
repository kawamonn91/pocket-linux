package main

import (
	"fmt"
	"os"
	"syscall"
)

// spawnRequest はプロセスを 1 つ起こす依頼。エージェント（VM のルート）と cinit（コンテナの中）で共通。
type spawnRequest struct {
	ID   int      `json:"id"`
	Argv []string `json:"argv"`
	Env  []string `json:"env"`
	Cwd  string   `json:"cwd"`
	Pty  bool     `json:"pty"`
	Cols int      `json:"cols"`
	Rows int      `json:"rows"`
}

// spawned は起こしたプロセス。Pty なら Master、そうでなければ Stdin・Stdout・Stderr を使う。
type spawned struct {
	Pid    int
	Master *os.File
	Stdin  *os.File
	Stdout *os.File
	Stderr *os.File
	Done   <-chan syscall.WaitStatus
}

func (s *spawned) files() []*os.File {
	if s.Master != nil {
		return []*os.File{s.Master}
	}
	return []*os.File{s.Stdin, s.Stdout, s.Stderr}
}

// spawnProcess は呼んだプロセスの名前空間・ルートの中でプロセスを起こす。
func spawnProcess(r spawnRequest) (*spawned, error) {
	if len(r.Argv) == 0 {
		return nil, fmt.Errorf("argv が空です")
	}
	exe, err := lookPath(r.Argv[0], r.Env)
	if err != nil {
		return nil, err
	}
	cwd := r.Cwd
	if cwd == "" {
		cwd = "/"
	}
	if st, err := os.Stat(cwd); err != nil || !st.IsDir() {
		cwd = "/"
	}
	attr := &syscall.ProcAttr{Dir: cwd, Env: r.Env, Sys: &syscall.SysProcAttr{Setsid: true}}

	if r.Pty {
		master, slave, err := openPty()
		if err != nil {
			return nil, fmt.Errorf("PTY を作れません: %w", err)
		}
		_ = setWinsize(master, r.Cols, r.Rows)
		fd := slave.Fd()
		attr.Files = []uintptr{fd, fd, fd}
		attr.Sys.Setctty = true
		attr.Sys.Ctty = 0
		pid, done, err := forkExec(exe, r.Argv, attr)
		slave.Close()
		if err != nil {
			master.Close()
			return nil, err
		}
		return &spawned{Pid: pid, Master: master, Done: done}, nil
	}

	inR, inW, err := os.Pipe()
	if err != nil {
		return nil, err
	}
	outR, outW, err := os.Pipe()
	if err != nil {
		inR.Close()
		inW.Close()
		return nil, err
	}
	errR, errW, err := os.Pipe()
	if err != nil {
		inR.Close()
		inW.Close()
		outR.Close()
		outW.Close()
		return nil, err
	}
	attr.Files = []uintptr{inR.Fd(), outW.Fd(), errW.Fd()}
	pid, done, err := forkExec(exe, r.Argv, attr)
	inR.Close()
	outW.Close()
	errW.Close()
	if err != nil {
		inW.Close()
		outR.Close()
		errR.Close()
		return nil, err
	}
	return &spawned{Pid: pid, Stdin: inW, Stdout: outR, Stderr: errR, Done: done}, nil
}
