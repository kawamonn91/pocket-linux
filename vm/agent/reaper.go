package main

import (
	"sync"
	"syscall"
)

// 子プロセスの終了を受け取る。PID 1 は、自分が起こしたもの以外（親が先に終わった孤児）も回収しなければならない。
// os/exec の Wait と wait4(-1) の取り合いを避けるため、子は必ず forkExec で起こし、ここで待つ。
type reaper struct {
	mu      sync.Mutex
	waiters map[int]chan syscall.WaitStatus
	wake    chan struct{}
}

var children = &reaper{
	waiters: map[int]chan syscall.WaitStatus{},
	wake:    make(chan struct{}, 1),
}

// forkExec はプロセスを起こし、終わったときの状態を受け取るチャンネルを返す。
// 起こしてから登録するまで mu を持っているので、すぐ終わった子でも取りこぼさない
// （loop は登録が済むまで mu を待つ）。
func forkExec(argv0 string, argv []string, attr *syscall.ProcAttr) (int, <-chan syscall.WaitStatus, error) {
	ch := make(chan syscall.WaitStatus, 1)
	children.mu.Lock()
	pid, err := syscall.ForkExec(argv0, argv, attr)
	if err != nil {
		children.mu.Unlock()
		return 0, nil, err
	}
	children.waiters[pid] = ch
	children.mu.Unlock()
	select {
	case children.wake <- struct{}{}:
	default:
	}
	return pid, ch, nil
}

func (r *reaper) loop() {
	for {
		var ws syscall.WaitStatus
		pid, err := syscall.Wait4(-1, &ws, 0, nil)
		if err == syscall.EINTR {
			continue
		}
		if err == syscall.ECHILD {
			<-r.wake // 子ができるまで待つ（何もしていないときに CPU を使わない）
			continue
		}
		if err != nil || pid <= 0 {
			continue
		}
		r.mu.Lock()
		if ch, ok := r.waiters[pid]; ok {
			delete(r.waiters, pid)
			ch <- ws
		}
		// 登録の無いものは孤児。回収するだけでよい
		r.mu.Unlock()
	}
}

func exitFromStatus(ws syscall.WaitStatus) exitMessage {
	if ws.Signaled() {
		return exitMessage{Code: 128 + int(ws.Signal()), Signal: int(ws.Signal())}
	}
	return exitMessage{Code: ws.ExitStatus()}
}
