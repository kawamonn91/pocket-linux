package main

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

// フレームの形: [長さ u32 BE][種類 u8][チャンネル u32 BE][中身]。長さは種類以降のバイト数。
const (
	frameHeader = 5
	maxPayload  = 64 * 1024
)

// フレームの種類（docs/vm-protocol.md と app の AgentChannel.kt と同じ値）
const (
	tHello  byte = 1  // アプリ → ゲスト（チャンネル 0）。JSON
	tOpen   byte = 2  // アプリ → ゲスト。JSON（op ごとの中身）
	tData   byte = 3  // 両方向。アプリ → ゲストは標準入力、ゲスト → アプリは標準出力
	tEOF    byte = 4  // アプリ → ゲスト。標準入力を閉じる
	tResize byte = 5  // アプリ → ゲスト。JSON {cols, rows}
	tSignal byte = 6  // アプリ → ゲスト。JSON {signal}
	tClose  byte = 7  // アプリ → ゲスト。チャンネルを捨てる（プロセスは止める）
	tExit   byte = 8  // ゲスト → アプリ。JSON {code, signal, error}。チャンネルの最後
	tStderr byte = 9  // ゲスト → アプリ。標準エラー出力
	tEvent  byte = 10 // ゲスト → アプリ（チャンネル 0）。JSON {type, ...}
)

type frame struct {
	typ     byte
	channel uint32
	payload []byte
}

func readFrame(r io.Reader) (frame, error) {
	var head [4 + frameHeader]byte
	if _, err := io.ReadFull(r, head[:]); err != nil {
		return frame{}, err
	}
	n := binary.BigEndian.Uint32(head[0:4])
	if n < frameHeader || n > frameHeader+maxPayload {
		return frame{}, fmt.Errorf("フレームの長さがおかしい: %d", n)
	}
	f := frame{typ: head[4], channel: binary.BigEndian.Uint32(head[5:9])}
	if n > frameHeader {
		f.payload = make([]byte, n-frameHeader)
		if _, err := io.ReadFull(r, f.payload); err != nil {
			return frame{}, err
		}
	}
	return f, nil
}

func encodeFrame(typ byte, channel uint32, payload []byte) []byte {
	buf := make([]byte, 4+frameHeader+len(payload))
	binary.BigEndian.PutUint32(buf[0:4], uint32(frameHeader+len(payload)))
	buf[4] = typ
	binary.BigEndian.PutUint32(buf[5:9], channel)
	copy(buf[9:], payload)
	return buf
}

// frameWriter は複数の goroutine から 1 本のチャンネルへ書く。
type frameWriter struct {
	mu sync.Mutex
	w  io.Writer
}

func (fw *frameWriter) send(typ byte, channel uint32, payload []byte) error {
	fw.mu.Lock()
	defer fw.mu.Unlock()
	for len(payload) > maxPayload {
		if _, err := fw.w.Write(encodeFrame(typ, channel, payload[:maxPayload])); err != nil {
			return err
		}
		payload = payload[maxPayload:]
	}
	_, err := fw.w.Write(encodeFrame(typ, channel, payload))
	return err
}

func (fw *frameWriter) sendJSON(typ byte, channel uint32, v any) error {
	b, err := json.Marshal(v)
	if err != nil {
		return err
	}
	return fw.send(typ, channel, b)
}

// openRequest は OPEN の中身。op によって使う項目が違う。
type openRequest struct {
	Op        string   `json:"op"`
	Container string   `json:"container,omitempty"`
	Hostname  string   `json:"hostname,omitempty"`
	Argv      []string `json:"argv,omitempty"`
	Env       []string `json:"env,omitempty"`
	Cwd       string   `json:"cwd,omitempty"`
	Pty       bool     `json:"pty,omitempty"`
	Cols      int      `json:"cols,omitempty"`
	Rows      int      `json:"rows,omitempty"`
	Path      string   `json:"path,omitempty"`
	Mode      uint32   `json:"mode,omitempty"`
	Exclude   []string `json:"exclude,omitempty"`
	Listen    string   `json:"listen,omitempty"`
	Target    string   `json:"target,omitempty"`
	Time      int64    `json:"time,omitempty"`
	Servers   []string `json:"servers,omitempty"`
	// Shared は VM のルートの exec を umask 0 で動かす（Drive の同期フォルダに書く rclone 用）
	Shared bool `json:"shared,omitempty"`
}

type helloMessage struct {
	Time int64    `json:"time"` // UNIX 時刻（ミリ秒）
	DNS  []string `json:"dns"`
}

type exitMessage struct {
	Code   int    `json:"code"`
	Signal int    `json:"signal,omitempty"`
	Error  string `json:"error,omitempty"`
}

var errClosed = errors.New("チャンネルは閉じられました")
