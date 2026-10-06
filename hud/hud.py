#!/usr/bin/env python3
"""
HUD backend: polls the car (or the simulator) and streams snapshots to the display page.

    python3 hud.py --sim                      # no car: simulated drive
    python3 hud.py --port /dev/ttyUSB0        # USB ELM327 / OBDLink
    python3 hud.py --port /dev/rfcomm0        # Bluetooth Classic ELM327, bound with rfcomm

Then open http://127.0.0.1:8080/ . The page reads /events (Server-Sent Events), so the
browser needs nothing but the URL. Standard library only.
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import obd
import sim

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")

FIELDS = ("speed", "rpm", "throttle", "load", "coolant", "soc", "volt", "fuel",
          "ambient", "iat", "mil", "dtc_count")


class State(object):
    def __init__(self, source):
        self.lock = threading.Lock()
        self.d = dict((k, None) for k in FIELDS)
        self.d.update({"src": source, "link": "starting", "adapter": "", "supported": []})

    def update(self, values):
        with self.lock:
            self.d.update(values)

    def snapshot(self):
        with self.lock:
            s = dict(self.d)
        s["t"] = int(time.time() * 1000)
        return s


# ---------------------------------------------------------------------- pollers

def run_sim(state):
    drive = sim.Drive()
    state.update({"link": "ok", "adapter": "simulator"})
    last = time.time()
    while True:
        now = time.time()
        state.update(drive.step(now - last))
        last = now
        time.sleep(0.1)


def run_obd(state, port, baud, custom):
    while True:
        elm = None
        try:
            state.update({"link": "connecting"})
            elm = obd.ELM327(port, baud)
            ver = elm.init()
            supported = set()
            for base in (0x00, 0x20, 0x40):
                if base and (base not in supported):
                    break                      # the previous block says the next is absent
                data = elm.query("01%02X" % base, timeout=6.0)   # first query may search
                if not data or len(data) < 4:
                    break
                supported |= obd.supported_from(base, data)
            state.update({"link": "ok", "adapter": ver,
                          "supported": sorted("%02X" % p for p in supported)})

            fast = [p for p in obd.FAST if p in supported]
            slow = [p for p in obd.SLOW if p in supported]
            status = [p for p in obd.STATUS if p in supported]
            next_slow = next_status = 0.0
            while True:
                now = time.time()
                todo = list(fast)
                slow_turn = now >= next_slow
                if slow_turn:
                    todo += slow
                    next_slow = now + 2.0
                if now >= next_status:
                    todo += status
                    next_status = now + 15.0
                for pid in todo:
                    data = elm.query("01%02X" % pid)
                    if data:
                        state.update(obd.decode(pid, data))
                if slow_turn:                      # custom PIDs ride the 2 s cadence, no faster
                    for spec in custom:
                        if spec.get("header"):
                            elm.at("SH" + spec["header"])
                        data = elm.query(spec["request"])
                        if data:
                            state.update({spec["field"]: obd.custom_value(spec, data)})
                    if custom:
                        elm.at("SH7DF")            # back to the functional broadcast header
                time.sleep(0.05)                   # keeps the bus load modest
        except obd.RefusedCommand:
            raise                                  # a refused request is a bug; never paper over it
        except Exception as e:                     # adapter unplugged, car off, timeouts
            state.update({"link": "error: %s" % e})
        finally:
            if elm:
                elm.close()
        time.sleep(5)


# ---------------------------------------------------------------------- web

class Handler(BaseHTTPRequestHandler):
    state = None

    def log_message(self, *a):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/events":
            return self.events()
        if path == "/state":
            return self.send(200, "application/json", json.dumps(self.state.snapshot()).encode())
        if path == "/":
            path = "/index.html"
        full = os.path.realpath(os.path.join(WEB, path.lstrip("/")))
        if not full.startswith(os.path.realpath(WEB) + os.sep) or not os.path.isfile(full):
            return self.send(404, "text/plain", b"not found")
        ctype = {".html": "text/html; charset=utf-8", ".js": "text/javascript",
                 ".css": "text/css", ".ttf": "font/ttf", ".txt": "text/plain"}.get(
                     os.path.splitext(full)[1], "application/octet-stream")
        with open(full, "rb") as f:
            self.send(200, ctype, f.read())

    def send(self, code, ctype, body):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def events(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            while True:
                self.wfile.write(b"data: " + json.dumps(self.state.snapshot()).encode() + b"\n\n")
                self.wfile.flush()
                time.sleep(0.1)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--sim", action="store_true", help="simulated drive, no car needed")
    ap.add_argument("--port", help="serial device of the OBD adapter")
    ap.add_argument("--baud", type=int, default=38400)
    ap.add_argument("--config", default=os.path.join(HERE, "config.json"),
                    help="optional manufacturer PIDs (see config.example.json)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--http", type=int, default=8080)
    a = ap.parse_args()
    if not a.sim and not a.port:
        ap.error("give --port /dev/ttyUSB0 (or --sim)")

    custom = []
    if os.path.isfile(a.config):
        with open(a.config) as f:
            custom = json.load(f).get("custom_pids", [])
        for spec in custom:
            obd.check_obd(spec["request"])         # refuse a non-read at startup, loudly

    state = State("sim" if a.sim else "obd")
    target = run_sim if a.sim else run_obd
    args = (state,) if a.sim else (state, a.port, a.baud, custom)
    threading.Thread(target=target, args=args, daemon=True).start()

    Handler.state = state
    srv = ThreadingHTTPServer((a.host, a.http), Handler)
    srv.daemon_threads = True
    print("HUD on http://%s:%d/  (source: %s)" % (a.host, a.http, "simulator" if a.sim else a.port))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    sys.exit(main())
