package main

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

var bootTime = time.Now()

// dataMounted は /data（データ用のディスク）が使えるか。ready でアプリに知らせる。
var dataMounted bool

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "pocket-agent: "+format+"\n", args...)
}

func uptimeMillis() int64 { return time.Since(bootTime).Milliseconds() }

// runInit は VM の init。終わってはいけない（PID 1 が終わるとカーネルが止まる）。
func runInit() {
	logf("版 %s を起動しています", Version)
	mountBase()
	writeSysctls()
	bringUpLoopback()
	setupData()
	setupStorage()
	for _, d := range []string{"/data/containers", "/data/drive", "/data/pocket-dev", "/data/system/home"} {
		_ = os.MkdirAll(d, 0o755)
	}
	_ = os.MkdirAll("/run/pocket/open", 0o1777)
	_ = os.Chmod("/run/pocket/open", 0o1777)

	go children.loop()
	go clockLoop()

	port, err := openAgentPort()
	for err != nil {
		logf("制御用のポートを開けません: %v（再試行します）", err)
		time.Sleep(time.Second)
		port, err = openAgentPort()
	}
	srv := newServer(port)
	go watchOpenRequests(srv)
	logf("準備ができました（%d ms）", uptimeMillis())
	srv.serve(port)
}

func mountBase() {
	type m struct {
		src, target, fstype string
		flags               uintptr
		data                string
	}
	mounts := []m{
		{"proc", "/proc", "proc", syscall.MS_NOSUID | syscall.MS_NODEV | syscall.MS_NOEXEC, ""},
		{"sysfs", "/sys", "sysfs", syscall.MS_NOSUID | syscall.MS_NODEV | syscall.MS_NOEXEC, ""},
		{"devtmpfs", "/dev", "devtmpfs", syscall.MS_NOSUID, "mode=0755"},
		{"devpts", "/dev/pts", "devpts", syscall.MS_NOSUID | syscall.MS_NOEXEC, "ptmxmode=0666,mode=0620,gid=5"},
		{"shm", "/dev/shm", "tmpfs", syscall.MS_NOSUID | syscall.MS_NODEV, "mode=1777"},
		{"run", "/run", "tmpfs", syscall.MS_NOSUID | syscall.MS_NODEV, "mode=0755"},
		{"tmp", "/tmp", "tmpfs", syscall.MS_NOSUID | syscall.MS_NODEV, "mode=1777"},
		{"cgroup2", "/sys/fs/cgroup", "cgroup2", syscall.MS_NOSUID | syscall.MS_NODEV | syscall.MS_NOEXEC, ""},
	}
	for _, x := range mounts {
		_ = os.MkdirAll(x.target, 0o755)
		if err := syscall.Mount(x.src, x.target, x.fstype, x.flags, x.data); err != nil && err != syscall.EBUSY {
			logf("%s を mount できません: %v", x.target, err)
		}
	}
	for link, target := range map[string]string{
		"/dev/fd": "/proc/self/fd", "/dev/stdin": "/proc/self/fd/0",
		"/dev/stdout": "/proc/self/fd/1", "/dev/stderr": "/proc/self/fd/2",
	} {
		_ = os.Symlink(target, link)
	}
}

func writeSysctls() {
	for k, v := range map[string]string{
		"/proc/sys/fs/inotify/max_user_watches":   "524288",
		"/proc/sys/fs/inotify/max_user_instances": "1024",
		"/proc/sys/net/ipv4/ping_group_range":     "0 2147483647",
		"/proc/sys/net/ipv4/ip_forward":           "1",
		"/proc/sys/vm/swappiness":                 "100",
	} {
		_ = os.WriteFile(k, []byte(v), 0o644)
	}
}

func bringUpLoopback() {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM, 0)
	if err != nil {
		return
	}
	defer syscall.Close(fd)
	var ifr [40]byte
	copy(ifr[:], "lo")
	if ioctl(uintptr(fd), syscall.SIOCGIFFLAGS, uintptr(unsafe.Pointer(&ifr[0]))) != nil {
		return
	}
	flags := binary.LittleEndian.Uint16(ifr[16:18]) | syscall.IFF_UP
	binary.LittleEndian.PutUint16(ifr[16:18], flags)
	_ = ioctl(uintptr(fd), syscall.SIOCSIFFLAGS, uintptr(unsafe.Pointer(&ifr[0])))
}

// findDisk は virtio-blk の serial（QEMU の -device ...,serial=）で /dev/vdX を探す。
func findDisk(serial string) string {
	matches, _ := filepath.Glob("/sys/block/vd*/serial")
	for _, m := range matches {
		b, err := os.ReadFile(m)
		if err == nil && strings.TrimSpace(string(b)) == serial {
			return "/dev/" + path.Base(path.Dir(m))
		}
	}
	return ""
}

// setupData はデータ用のディスク（ext4）を /data に mount し、ファイルが大きくなっていれば広げる。
func setupData() {
	dev := findDisk("pocket-data")
	if dev == "" {
		logf("データ用のディスクが見つかりません")
		return
	}
	if err := syscall.Mount(dev, dataDir, "ext4", syscall.MS_NOATIME, "noinit_itable"); err != nil {
		logf("データ用のディスクを mount できません: %v", err)
		return
	}
	dataMounted = true
	if err := growData(dev); err != nil {
		logf("データ用のディスクを広げられません: %v", err)
	}
}

func growData(dev string) error {
	f, err := os.Open(dev)
	if err != nil {
		return err
	}
	defer f.Close()
	var devBytes uint64
	if err := ioctl(f.Fd(), ioctlBLKGETSIZE64, uintptr(unsafe.Pointer(&devBytes))); err != nil {
		return err
	}
	sb := make([]byte, 1024)
	if _, err := f.ReadAt(sb, 1024); err != nil {
		return err
	}
	if binary.LittleEndian.Uint16(sb[0x38:0x3a]) != 0xEF53 {
		return fmt.Errorf("ext4 ではありません")
	}
	blockSize := uint64(1024) << binary.LittleEndian.Uint32(sb[0x18:0x1c])
	blocks := uint64(binary.LittleEndian.Uint32(sb[0x04:0x08]))
	const featureIncompat64bit = 0x80
	if binary.LittleEndian.Uint32(sb[0x60:0x64])&featureIncompat64bit != 0 {
		blocks |= uint64(binary.LittleEndian.Uint32(sb[0x150:0x154])) << 32
	}
	want := devBytes / blockSize
	if want <= blocks+(8<<20)/blockSize { // 8 MB 未満の差は無視する
		return nil
	}
	dir, err := os.Open(dataDir)
	if err != nil {
		return err
	}
	defer dir.Close()
	logf("データ用のディスクを %d MB から %d MB に広げます", blocks*blockSize>>20, want*blockSize>>20)
	return ioctl(dir.Fd(), ioctlEXT4RESIZEFS, uintptr(unsafe.Pointer(&want)))
}

// setupStorage は端末のストレージ（9p、タグ storage）があれば /mnt/storage に mount する。
func setupStorage() {
	tags, _ := filepath.Glob("/sys/bus/virtio/drivers/9pnet_virtio/virtio*/mount_tag")
	for _, t := range tags {
		b, _ := os.ReadFile(t)
		if string(bytes.TrimRight(b, "\x00\n")) != "storage" {
			continue
		}
		_ = os.MkdirAll("/mnt/storage", 0o755)
		if err := syscall.Mount("storage", "/mnt/storage", "9p", syscall.MS_NOATIME,
			"trans=virtio,version=9p2000.L,msize=262144,cache=none"); err != nil {
			logf("端末のストレージを mount できません: %v", err)
		}
		return
	}
}

func storageMounted() bool {
	b, err := os.ReadFile("/proc/self/mounts")
	return err == nil && bytes.Contains(b, []byte(" /mnt/storage 9p "))
}

func openAgentPort() (*os.File, error) {
	names, _ := filepath.Glob("/sys/class/virtio-ports/*/name")
	for _, n := range names {
		b, err := os.ReadFile(n)
		if err == nil && strings.TrimSpace(string(b)) == "pocket.agent" {
			return os.OpenFile("/dev/"+path.Base(path.Dir(n)), os.O_RDWR, 0)
		}
	}
	return nil, fmt.Errorf("virtio-serial のポート pocket.agent がありません")
}

// --- 時刻

func setClock(t time.Time) {
	now := time.Now()
	if d := t.Sub(now); d < time.Second && d > -time.Second {
		return
	}
	tv := syscall.NsecToTimeval(t.UnixNano())
	if err := syscall.Settimeofday(&tv); err != nil {
		logf("時刻を合わせられません: %v", err)
	}
}

// clockLoop は RTC（QEMU が端末の時計を見せる）に合わせ続ける。端末がスリープすると VM の時計が遅れるため。
func clockLoop() {
	for {
		if t, err := readRTC(); err == nil {
			setClock(t)
		}
		time.Sleep(30 * time.Second)
	}
}

func readRTC() (time.Time, error) {
	f, err := os.Open("/dev/rtc0")
	if err != nil {
		return time.Time{}, err
	}
	defer f.Close()
	var tm [9]int32 // struct rtc_time
	if err := ioctl(f.Fd(), ioctlRTCRDTIME, uintptr(unsafe.Pointer(&tm[0]))); err != nil {
		return time.Time{}, err
	}
	return time.Date(int(tm[5])+1900, time.Month(tm[4]+1), int(tm[3]), int(tm[2]), int(tm[1]), int(tm[0]), 0, time.UTC), nil
}

// --- 停止

func shutdown() {
	logf("停止します")
	containers.stopAll()
	syscall.Sync()
	if err := syscall.Mount("", dataDir, "", syscall.MS_REMOUNT|syscall.MS_RDONLY, ""); err != nil {
		logf("/data を読み取り専用にできません: %v", err)
	}
	syscall.Sync()
	_ = syscall.Reboot(syscall.LINUX_REBOOT_CMD_POWER_OFF)
}
