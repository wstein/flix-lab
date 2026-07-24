#!/usr/bin/env python3
"""
Drives debug-adapter/bin/flix-debug-adapter through a real debug session
(initialize, attach, setBreakpoints, configurationDone, wait for a
breakpoint hit, stackTrace/scopes/variables, continue, disconnect),
simulating exactly what VS Code's DAP client would send.

Usage: dap_client_test.py <path-to-flix-file> <line> [host] [port]

Expects a Flix program already running, suspended, with a JDWP agent
listening on the given host/port (see the "flix: debug ..." VS Code tasks,
or run manually:
  JAVA_TOOL_OPTIONS='-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005' \\
    ./scripts/flix-fork run --Xdebug --yes --entrypoint demo
"""
import json
import subprocess
import sys
import threading
import time
from pathlib import Path

ADAPTER = Path(__file__).resolve().parent.parent / "bin" / "flix-debug-adapter"


class DapClient:
    def __init__(self, proc):
        self.proc = proc
        self.seq = 0
        self.lock = threading.Lock()
        self.events = []
        self.responses = {}
        self.cond = threading.Condition()
        self.reader = threading.Thread(target=self._read_loop, daemon=True)
        self.reader.start()

    def send(self, command, arguments=None):
        with self.lock:
            self.seq += 1
            seq = self.seq
        msg = {"seq": seq, "type": "request", "command": command}
        if arguments is not None:
            msg["arguments"] = arguments
        body = json.dumps(msg).encode("utf-8")
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("utf-8")
        self.proc.stdin.write(header + body)
        self.proc.stdin.flush()
        return seq

    def _read_loop(self):
        buf = b""
        f = self.proc.stdout
        while True:
            line = f.readline()
            if not line:
                return
            if line.strip() == b"":
                continue
            if line.lower().startswith(b"content-length"):
                length = int(line.split(b":")[1].strip())
                f.readline()  # blank line
                body = f.read(length)
                msg = json.loads(body.decode("utf-8"))
                with self.cond:
                    if msg.get("type") == "event":
                        print(f"   [event received: {msg.get('event')}]", file=sys.stderr)
                        self.events.append(msg)
                    elif msg.get("type") == "response":
                        self.responses[msg["request_seq"]] = msg
                    self.cond.notify_all()

    def wait_response(self, seq, timeout=10):
        with self.cond:
            end = time.time() + timeout
            while seq not in self.responses:
                remaining = end - time.time()
                if remaining <= 0:
                    raise TimeoutError(f"no response to request {seq}")
                self.cond.wait(remaining)
            return self.responses.pop(seq)

    def wait_event(self, name, timeout=15):
        with self.cond:
            end = time.time() + timeout
            while True:
                for i, e in enumerate(self.events):
                    if e.get("event") == name:
                        return self.events.pop(i)
                remaining = end - time.time()
                if remaining <= 0:
                    raise TimeoutError(f"no '{name}' event")
                self.cond.wait(remaining)

    def request(self, command, arguments=None, timeout=10):
        seq = self.send(command, arguments)
        return self.wait_response(seq, timeout)


def main():
    flix_file = sys.argv[1]
    line = int(sys.argv[2])
    host = sys.argv[3] if len(sys.argv) > 3 else "localhost"
    port = int(sys.argv[4]) if len(sys.argv) > 4 else 5005

    proc = subprocess.Popen(
        [str(ADAPTER)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr,
    )
    try:
        run_session(proc, flix_file, line, host, port)
    finally:
        proc.terminate()


def run_session(proc, flix_file, line, host, port):
    client = DapClient(proc)

    print("-> initialize")
    r = client.request("initialize", {})
    assert r["success"], r
    client.wait_event("initialized")

    print(f"-> attach {host}:{port}")
    r = client.request("attach", {"hostName": host, "port": port})
    assert r["success"], r

    print(f"-> setBreakpoints {flix_file}:{line}")
    r = client.request("setBreakpoints", {
        "source": {"path": flix_file},
        "breakpoints": [{"line": line}],
    })
    assert r["success"], r
    print("   result:", json.dumps(r["body"], indent=2))

    print("-> configurationDone")
    r = client.request("configurationDone", {})
    assert r["success"], r

    print("-- waiting for 'breakpoint' event (deferred verification) --")
    changed = client.wait_event("breakpoint", timeout=15)
    print("   breakpoint changed:", json.dumps(changed["body"], indent=2))

    print("-- waiting for 'stopped' event (breakpoint hit) --")
    stopped = client.wait_event("stopped", timeout=30)
    print("   stopped:", json.dumps(stopped["body"], indent=2))
    thread_id = stopped["body"]["threadId"]

    print("-> stackTrace")
    r = client.request("stackTrace", {"threadId": thread_id})
    frames = r["body"]["stackFrames"]
    for f in frames[:5]:
        print(f"   {f['name']}  {f['source']['path']}:{f['line']}")
    top = frames[0]

    print("-> scopes")
    r = client.request("scopes", {"frameId": top["id"]})
    scope = r["body"]["scopes"][0]

    print("-> variables")
    r = client.request("variables", {"variablesReference": scope["variablesReference"]})
    for v in r["body"]["variables"]:
        print(f"   {v['name']}: {v['value']}  ({v['type']})")

    print("-> continue")
    r = client.request("continue", {"threadId": thread_id})
    assert r["success"], r

    print("-- waiting for a second 'stopped' event (breakpoint should recur) --")
    stopped2 = client.wait_event("stopped", timeout=30)
    print("   stopped again:", json.dumps(stopped2["body"], indent=2))

    print("-> disconnect")
    client.send("disconnect", {})
    time.sleep(1)
    print("OK")


if __name__ == "__main__":
    main()
