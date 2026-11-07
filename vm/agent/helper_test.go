package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestShareTree(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "dir")
	if err := os.Mkdir(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(dir, "file.txt")
	script := filepath.Join(dir, "run.sh")
	outside := filepath.Join(t.TempDir(), "outside.txt")
	for name, mode := range map[string]os.FileMode{file: 0o644, script: 0o755, outside: 0o600} {
		if err := os.WriteFile(name, []byte("x"), mode); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(name, mode); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink(outside, filepath.Join(dir, "link")); err != nil {
		t.Fatal(err)
	}

	if err := shareTree(root); err != nil {
		t.Fatal(err)
	}

	want := map[string]os.FileMode{
		root:    0o777,
		dir:     0o777,
		file:    0o666,
		script:  0o777,
		outside: 0o600, // リンクの先は変えない
	}
	for name, mode := range want {
		info, err := os.Stat(name)
		if err != nil {
			t.Fatal(err)
		}
		if got := info.Mode().Perm(); got != mode {
			t.Errorf("%s: 権限が %o（期待は %o）", name, got, mode)
		}
	}
}

func TestMakeDir(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "a", "b")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := makeDir(dir, []string{"777"}); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got := info.Mode().Perm(); got != 0o777 {
		t.Errorf("すでにあるフォルダの権限が %o（期待は 777）", got)
	}
	if err := makeDir(filepath.Join(root, "x"), []string{"nine"}); err == nil {
		t.Error("不正な権限でエラーにならない")
	}
}
