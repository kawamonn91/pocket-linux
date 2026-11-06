"""pocket-agent の制御用のチャンネルを話す、テスト用の小さなクライアント（docs/vm-protocol.md）。

アプリの AgentChannel.kt と同じ約束事で、CI の起動テストに使う。
"""

import json
import socket
import struct
import threading
import time

HELLO, OPEN, DATA, EOF, RESIZE, SIGNAL, CLOSE, EXIT, STDERR, EVENT = range(1, 11)


class Result:
    def __init__(self):
        self.stdout = bytearray()
        self.stderr = bytearray()
        self.exit = None
        self.done = threading.Event()

    @property
    def text(self):
        return self.stdout.decode("utf-8", "replace")


class AgentClient:
    def __init__(self, path, timeout=600):
        deadline = time.time() + 30
        while True:
            try:
                self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                self.sock.connect(path)
                break
            except OSError:
                if time.time() > deadline:
                    raise
                time.sleep(0.2)
        self.timeout = timeout
        self.lock = threading.Lock()
        self.next = 1
        self.results = {}
        self.events = []
        self.event_cv = threading.Condition()
        threading.Thread(target=self._reader, daemon=True).start()

    def _send(self, typ, chan, payload=b""):
        if isinstance(payload, (dict, list)):
            payload = json.dumps(payload).encode()
        for i in range(0, max(len(payload), 1), 65536):
            part = payload[i:i + 65536]
            with self.lock:
                self.sock.sendall(struct.pack(">IBI", 5 + len(part), typ, chan) + part)

    def _recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise EOFError
            buf += chunk
        return bytes(buf)

    def _reader(self):
        try:
            while True:
                length, typ, chan = struct.unpack(">IBI", self._recv_exact(9))
                payload = self._recv_exact(length - 5)
                if typ == EVENT:
                    with self.event_cv:
                        self.events.append(json.loads(payload))
                        self.event_cv.notify_all()
                    continue
                r = self.results.get(chan)
                if r is None:
                    continue
                if typ == DATA:
                    r.stdout += payload
                elif typ == STDERR:
                    r.stderr += payload
                elif typ == EXIT:
                    r.exit = json.loads(payload)
                    r.done.set()
        except (EOFError, OSError):
            for r in self.results.values():
                r.done.set()

    def wait_event(self, kind, timeout):
        deadline = time.time() + timeout
        with self.event_cv:
            while True:
                for e in self.events:
                    if e.get("type") == kind:
                        self.events.remove(e)
                        return e
                left = deadline - time.time()
                if left <= 0:
                    raise TimeoutError(f"{kind} が来ません")
                self.event_cv.wait(left)

    def hello(self, dns):
        self._send(HELLO, 0, {"time": int(time.time() * 1000), "dns": dns})

    def open(self, req):
        with self.lock:
            chan = self.next
            self.next += 1
        r = Result()
        self.results[chan] = r
        self._send(OPEN, chan, req)
        return chan, r

    def call(self, req, stdin=None, timeout=None):
        chan, r = self.open(req)
        if stdin is not None:
            if hasattr(stdin, "read"):
                while True:
                    block = stdin.read(256 * 1024)
                    if not block:
                        break
                    self._send(DATA, chan, block)
            else:
                self._send(DATA, chan, stdin)
            self._send(EOF, chan)
        if not r.done.wait(timeout or self.timeout):
            raise TimeoutError(f"{req.get('op')} が終わりません")
        return r

    def write(self, chan, data):
        self._send(DATA, chan, data)

    def resize(self, chan, cols, rows):
        self._send(RESIZE, chan, {"cols": cols, "rows": rows})

    def signal(self, chan, sig):
        self._send(SIGNAL, chan, {"signal": sig})

    def close(self, chan):
        self._send(CLOSE, chan)
