#!/usr/bin/env python3
"""
Drive an MCP stdio server with raw JSON-RPC and dump the wire traffic.

Deliberately not the Inspector: this prints exactly what crossed the pipe, which is
what Phase 3 needs to diff. The Inspector is the right tool for interactive poking,
this is the right tool for capturing evidence.
"""
import json
import subprocess
import sys
import threading
import queue

PROTOCOL_VERSION = "2025-11-25"


class Server:
    def __init__(self, cmd):
        self.p = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        self.out = queue.Queue()
        self.err = []
        threading.Thread(target=self._pump_stdout, daemon=True).start()
        threading.Thread(target=self._pump_stderr, daemon=True).start()

    def _pump_stdout(self):
        for line in self.p.stdout:
            self.out.put(line.rstrip("\n"))
        self.out.put(None)

    def _pump_stderr(self):
        for line in self.p.stderr:
            self.err.append(line.rstrip("\n"))

    def send(self, obj):
        raw = json.dumps(obj, separators=(",", ":"))
        print(f"\n>>> {raw}")
        self.p.stdin.write(raw + "\n")
        self.p.stdin.flush()

    def recv(self, timeout=25):
        while True:
            try:
                line = self.out.get(timeout=timeout)
            except queue.Empty:
                print(f"!!! TIMEOUT after {timeout}s waiting for a response")
                return None
            if line is None:
                print("!!! server closed stdout")
                return None
            if not line.strip():
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                # This is the failure mode the stdio hygiene settings exist to prevent:
                # something that is not JSON-RPC landed on stdout.
                print(f"!!! NON-JSON ON STDOUT (wire corruption): {line!r}")
                continue
            print("<<< " + json.dumps(msg, indent=2, sort_keys=True))
            return msg

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        try:
            self.p.wait(timeout=10)
        except Exception:
            self.p.kill()


def main():
    cmd = sys.argv[1:]
    if not cmd:
        print("usage: mcp_probe.py <command to run server>")
        return 2

    s = Server(cmd)
    n = [0]

    def rpc(method, params=None):
        n[0] += 1
        msg = {"jsonrpc": "2.0", "id": n[0], "method": method}
        if params is not None:
            msg["params"] = params
        s.send(msg)
        return s.recv()

    def notify(method, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        s.send(msg)

    print("=" * 70)
    print("STEP 1 — initialize")
    print("=" * 70)
    init = rpc("initialize", {
        "protocolVersion": PROTOCOL_VERSION,
        "capabilities": {},
        "clientInfo": {"name": "fitmcp-probe", "version": "0.1.0"},
    })
    if init is None:
        print("\n--- server stderr ---")
        print("\n".join(s.err[-40:]))
        s.close()
        return 1

    notify("notifications/initialized")

    print("\n" + "=" * 70)
    print("STEP 2 — tools/list")
    print("=" * 70)
    rpc("tools/list")

    calls = [
        ("happy path: full range",
         {"name": "listActivities",
          "arguments": {"startDate": "2026-07-01", "endDate": "2026-07-31"}}),
        ("filter + limit (expect truncated=true)",
         {"name": "listActivities",
          "arguments": {"startDate": "2026-07-01", "endDate": "2026-07-31", "limit": 2}}),
        ("sport filter",
         {"name": "listActivities",
          "arguments": {"startDate": "2026-07-01", "endDate": "2026-07-31", "sport": "run"}}),
        ("empty range (expect [] / count 0 / truncated false)",
         {"name": "listActivities",
          "arguments": {"startDate": "2026-01-01", "endDate": "2026-01-31"}}),
        ("getActivity with null HR fields (stub-2)",
         {"name": "getActivity", "arguments": {"activityId": "stub-2"}}),
        ("getActivity zero distance -> null pace (stub-4)",
         {"name": "getActivity", "arguments": {"activityId": "stub-4"}}),
        ("summarizePeriod",
         {"name": "summarizePeriod",
          "arguments": {"startDate": "2026-07-01", "endDate": "2026-07-31"}}),
        ("ERROR: activity not found",
         {"name": "getActivity", "arguments": {"activityId": "nope"}}),
        ("ERROR: inverted date range",
         {"name": "summarizePeriod",
          "arguments": {"startDate": "2026-07-31", "endDate": "2026-07-01"}}),
        ("ERROR: unknown tool name (expect protocol error)",
         {"name": "noSuchTool", "arguments": {}}),
        ("ERROR: missing required arg (expect protocol error)",
         {"name": "getActivity", "arguments": {}}),
    ]

    print("\n" + "=" * 70)
    print("STEP 3 — tools/call")
    print("=" * 70)
    for label, params in calls:
        print(f"\n--- {label} ---")
        rpc("tools/call", params)

    s.close()
    if s.err:
        print("\n--- server stderr (tail) ---")
        print("\n".join(s.err[-30:]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
