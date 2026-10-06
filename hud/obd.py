"""
ELM327 OBD-II reader for the HUD. Standard library only, so the Pi needs nothing installed.

Read-only by construction. Every request to the car passes through `query()`, which refuses
any service outside READ_SERVICES before a byte is written. There is no other path to the
adapter for car traffic, and no configuration can widen the list: clearing trouble codes,
actuator tests, routine control, security access and writes are not "disabled", they are
unreachable.

Asking the car a question is still a frame on the bus -- this is not the passive listening
the head-unit app does. Standard mode 01 reads are what every OBD app sends while driving;
the polling rate here is kept modest on purpose.
"""

import os
import select
import termios
import time
import tty

# Services that only read. Anything else is refused before it reaches the adapter.
#   01 current data   03 stored codes   07 pending codes   09 vehicle info
#   0A permanent codes   22 read data by identifier (manufacturer PIDs)
READ_SERVICES = frozenset([0x01, 0x03, 0x07, 0x09, 0x0A, 0x22])

# Adapter configuration commands. These talk to the ELM327 itself, not to the car; the list
# is still closed so that nothing unexpected can be slipped through the AT channel.
AT_ALLOWED = ("Z", "E0", "L0", "S0", "S1", "H0", "H1", "SP0", "AT1", "AT2", "DPN", "RV", "I",
              "SH", "CRA", "AR", "ST")


class RefusedCommand(Exception):
    """Raised for any request that is not a read. Never caught inside this module."""


def check_obd(cmd):
    """Return the normalised hex request, or raise RefusedCommand. Pure, so it is testable."""
    c = cmd.replace(" ", "").upper()
    if len(c) < 2 or len(c) % 2 or any(ch not in "0123456789ABCDEF" for ch in c):
        raise RefusedCommand("not a hex request: %r" % cmd)
    service = int(c[:2], 16)
    if service not in READ_SERVICES:
        raise RefusedCommand("service %02X is not a read; refused" % service)
    return c


def check_at(cmd):
    c = cmd.replace(" ", "").upper()
    if c.startswith("AT"):
        c = c[2:]
    for p in AT_ALLOWED:
        if c == p or (p in ("SH", "CRA", "ST") and c.startswith(p)):
            return "AT" + c
    raise RefusedCommand("adapter command AT%s not on the list" % c)


# ---------------------------------------------------------------------- serial, no pyserial

class Serial(object):
    """Raw serial over termios: /dev/ttyUSB0, /dev/ttyACM0 or /dev/rfcomm0."""

    def __init__(self, path, baud=38400):
        self.fd = os.open(path, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
        tty.setraw(self.fd)
        a = termios.tcgetattr(self.fd)
        speed = getattr(termios, "B%d" % baud, termios.B38400)
        a[4] = a[5] = speed
        a[2] |= termios.CLOCAL | termios.CREAD
        termios.tcsetattr(self.fd, termios.TCSANOW, a)

    def write(self, data):
        os.write(self.fd, data)

    def read_until(self, token=b">", timeout=2.0):
        buf = b""
        end = time.time() + timeout
        while time.time() < end:
            r, _, _ = select.select([self.fd], [], [], max(0.0, end - time.time()))
            if not r:
                break
            try:
                chunk = os.read(self.fd, 256)
            except BlockingIOError:
                continue
            if not chunk:
                break
            buf += chunk
            if token in buf:
                break
        return buf

    def drain(self):
        while True:
            r, _, _ = select.select([self.fd], [], [], 0)
            if not r:
                return
            try:
                if not os.read(self.fd, 256):
                    return
            except BlockingIOError:
                return

    def close(self):
        try:
            os.close(self.fd)
        except OSError:
            pass


# ---------------------------------------------------------------------- ELM327

class ELM327(object):
    def __init__(self, port, baud=38400, timeout=2.0):
        self.ser = Serial(port, baud)
        self.timeout = timeout
        self.version = ""

    def _raw(self, line, timeout=None):
        self.ser.drain()
        self.ser.write((line + "\r").encode("ascii"))
        out = self.ser.read_until(b">", timeout or self.timeout)
        text = out.decode("ascii", "replace").replace(">", "")
        return [l.strip() for l in text.replace("\n", "\r").split("\r") if l.strip()]

    def at(self, cmd, timeout=None):
        return self._raw(check_at(cmd), timeout)

    def query(self, cmd, timeout=None):
        """Send a read request; return the data bytes of the first positive reply, or None."""
        c = check_obd(cmd)
        lines = self._raw(c, timeout)
        want = "%02X" % (int(c[:2], 16) + 0x40)        # positive reply = service + 0x40
        echo = c[2:]
        for l in lines:
            h = l.replace(" ", "").upper()
            if h.startswith("SEARCHING") or h.startswith("BUSINIT"):
                continue
            if h.startswith(want + echo) and all(ch in "0123456789ABCDEF" for ch in h):
                body = h[len(want) + len(echo):]
                return bytes(int(body[i:i + 2], 16) for i in range(0, len(body) - 1, 2))
        return None

    def init(self):
        # Always the full command: check_at strips exactly one leading "AT", so "AT1" would be
        # read as "1" and refused. ATAT1 is adaptive timing.
        self.version = " ".join(self.at("ATZ", timeout=4.0))
        for c in ("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0"):
            self.at(c)
        return self.version

    def close(self):
        self.ser.close()


# ---------------------------------------------------------------------- PIDs (service 01)

def _a(d):
    return d[0]


PIDS = {
    0x01: ("status", lambda d: d),                                  # handled specially
    0x04: ("load", lambda d: _a(d) * 100.0 / 255),
    0x05: ("coolant", lambda d: _a(d) - 40),
    0x0C: ("rpm", lambda d: (d[0] * 256 + d[1]) / 4.0),
    0x0D: ("speed", lambda d: _a(d)),
    0x0F: ("iat", lambda d: _a(d) - 40),
    0x11: ("throttle", lambda d: _a(d) * 100.0 / 255),
    0x2F: ("fuel", lambda d: _a(d) * 100.0 / 255),
    0x42: ("volt", lambda d: (d[0] * 256 + d[1]) / 1000.0),
    0x46: ("ambient", lambda d: _a(d) - 40),
    0x5B: ("soc", lambda d: _a(d) * 100.0 / 255),                   # hybrid battery pack
}

FAST = (0x0D, 0x0C, 0x11, 0x04)
SLOW = (0x05, 0x5B, 0x42, 0x2F, 0x46, 0x0F)
STATUS = (0x01,)


def decode(pid, data):
    name, fn = PIDS[pid]
    if pid == 0x01:
        return {"mil": bool(data[0] & 0x80), "dtc_count": data[0] & 0x7F}
    return {name: round(fn(data), 2)}


def supported_from(base, data):
    """Bitmap reply of 0100/0120/0140 -> set of supported PIDs."""
    out = set()
    for i in range(32):
        if data[i // 8] & (0x80 >> (i % 8)):
            out.add(base + 1 + i)
    return out


def custom_value(spec, data):
    """Manufacturer PID from config: big-endian slice * scale + offset. No eval anywhere."""
    start, n = spec.get("start", 0), spec.get("bytes", 1)
    raw = 0
    for b in data[start:start + n]:
        raw = raw * 256 + b
    return round(raw * float(spec.get("scale", 1)) + float(spec.get("offset", 0)), 2)
