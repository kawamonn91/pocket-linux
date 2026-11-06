package main

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	fw := &frameWriter{w: &buf}
	big := bytes.Repeat([]byte{7}, maxPayload+10)
	if err := fw.send(tData, 3, []byte("abc")); err != nil {
		t.Fatal(err)
	}
	if err := fw.send(tEOF, 3, nil); err != nil {
		t.Fatal(err)
	}
	if err := fw.send(tData, 4, big); err != nil {
		t.Fatal(err)
	}
	f, err := readFrame(&buf)
	if err != nil || f.typ != tData || f.channel != 3 || string(f.payload) != "abc" {
		t.Fatalf("1 つ目: %+v %v", f, err)
	}
	f, err = readFrame(&buf)
	if err != nil || f.typ != tEOF || f.channel != 3 || len(f.payload) != 0 {
		t.Fatalf("2 つ目: %+v %v", f, err)
	}
	// 上限を超える中身は分けて送る
	f, err = readFrame(&buf)
	if err != nil || len(f.payload) != maxPayload {
		t.Fatalf("3 つ目: %d %v", len(f.payload), err)
	}
	f, err = readFrame(&buf)
	if err != nil || len(f.payload) != 10 {
		t.Fatalf("4 つ目: %d %v", len(f.payload), err)
	}
}

func TestReadFrameRejectsBadLength(t *testing.T) {
	for _, n := range []byte{0, 4} {
		if _, err := readFrame(bytes.NewReader([]byte{0, 0, 0, n, 1, 0, 0, 0, 0})); err == nil {
			t.Fatalf("長さ %d を受け入れた", n)
		}
	}
	if _, err := readFrame(bytes.NewReader([]byte{0xff, 0, 0, 0, 1, 0, 0, 0, 0})); err == nil {
		t.Fatal("大きすぎる長さを受け入れた")
	}
}

func TestSecureJoinStaysInRoot(t *testing.T) {
	root := t.TempDir()
	os.MkdirAll(filepath.Join(root, "etc"), 0o755)
	os.MkdirAll(filepath.Join(root, "run/systemd"), 0o755)
	// よくある形: /etc/resolv.conf → ../run/systemd/stub（まだ無い）
	os.Symlink("../run/systemd/stub", filepath.Join(root, "etc/resolv.conf"))
	// 悪いイメージ: 外を指すリンク
	os.Symlink("/../../../../outside", filepath.Join(root, "evil"))
	os.Symlink("../../../outside", filepath.Join(root, "etc/evil2"))
	// ループ
	os.Symlink("loop", filepath.Join(root, "loop"))

	cases := map[string]string{
		"/etc/resolv.conf": "run/systemd/stub",
		"/evil":            "outside",
		"/evil/x":          "outside/x",
		"/etc/evil2":       "outside",
		"/../../etc":       "etc",
		"/no/such/dir":     "no/such/dir",
		"/":                "",
	}
	for in, want := range cases {
		got, err := secureJoin(root, in)
		if err != nil {
			t.Fatalf("%s: %v", in, err)
		}
		if got != filepath.Join(root, want) {
			t.Errorf("%s: %s（期待 %s）", in, got, filepath.Join(root, want))
		}
	}
	if _, err := secureJoin(root, "/loop"); err == nil {
		t.Error("ループで失敗しない")
	}
}

func TestSanitizeHostname(t *testing.T) {
	cases := map[string]string{"Ubuntu_24.04": "ubuntu-24-04", "---": "pocket", "": "pocket", "a-b": "a-b"}
	for in, want := range cases {
		if got := sanitizeHostname(in); got != want {
			t.Errorf("%q: %q（期待 %q）", in, got, want)
		}
	}
}
