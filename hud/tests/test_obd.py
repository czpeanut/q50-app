"""
Exercises the real serial path against a fake ELM327 on a pseudo-terminal, so nothing is
mocked between the reader and the "adapter". Run: python3 -m unittest discover -s tests
"""

import os
import sys
import threading
import time
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import obd  # noqa: E402

SUPPORTED = {0x01, 0x04, 0x05, 0x0C, 0x0D, 0x0F, 0x11, 0x20,
             0x2F, 0x40, 0x42, 0x46, 0x5B}


def bitmap(base):
    v = 0
    for i in range(32):
        if base + 1 + i in SUPPORTED:
            v |= 1 << (31 - i)
    return "%08X" % v


class FakeELM(threading.Thread):
    """Answers like an ELM327 with echo off. Records every line it receives."""

    def __init__(self, fd):
        super(FakeELM, self).__init__(daemon=True)
        self.fd = fd
        self.seen = []
        self.stop = False

    def reply(self, cmd):
        if cmd.startswith("AT"):
            return "ELM327 v1.5" if cmd == "ATZ" else "OK"
        table = {
            "0100": "41 00 " + bitmap(0x00),
            "0120": "41 20 " + bitmap(0x20),
            "0140": "41 40 " + bitmap(0x40),
            "010D": "41 0D 3C",                      # 60 km/h
            "010C": "41 0C 1A F8",                   # 1726 rpm
            "0105": "41 05 7B",                      # 83 C
            "015B": "41 5B 9A",                      # 60.4 %
            "0142": "41 42 37 3C",                   # 14.14 V
            "0101": "41 01 82 07 E5 00",             # MIL on, 2 codes
            "0111": "SEARCHING...\r41 11 33",        # first reply after a protocol search
            "0146": "NO DATA",
            "221234": "62 12 34 01 F4",              # manufacturer PID, 500 raw
        }
        return table.get(cmd, "?")

    def run(self):
        buf = b""
        while not self.stop:
            try:
                chunk = os.read(self.fd, 64)
            except OSError:
                return
            buf += chunk
            while b"\r" in buf:
                line, buf = buf.split(b"\r", 1)
                cmd = line.decode().strip().replace(" ", "").upper()
                if not cmd:
                    continue
                self.seen.append(cmd)
                os.write(self.fd, (self.reply(cmd) + "\r\r>").encode())


class Whitelist(unittest.TestCase):
    def test_reads_pass(self):
        for c in ("010D", "01 0c", "03", "07", "0902", "0A", "221234"):
            obd.check_obd(c)

    def test_everything_else_refused(self):
        # 04 clear codes, 08 on-board control, 10 session, 11 reset, 14 clear DTC info,
        # 27 security access, 2E write by identifier, 2F I/O control, 31 routine, 3B write
        for c in ("04", "0800", "1003", "1101", "14FFFFFF", "2701", "2E123400",
                  "2F123403", "3101FF00", "3B90", "85", "ZZ", "1"):
            with self.assertRaises(obd.RefusedCommand, msg=c):
                obd.check_obd(c)

    def test_adapter_commands(self):
        obd.check_at("ATZ")
        obd.check_at("SH7E4")
        for c in ("ATMA", "ATBD", "ATPP 0C SV 01", "ATWS"):
            with self.assertRaises(obd.RefusedCommand, msg=c):
                obd.check_at(c)


class OverSerial(unittest.TestCase):
    def setUp(self):
        self.master, slave = os.openpty()
        self.fake = FakeELM(self.master)
        self.fake.start()
        self.elm = obd.ELM327(os.ttyname(slave), timeout=1.0)
        os.close(slave)

    def tearDown(self):
        self.fake.stop = True
        self.elm.close()
        os.close(self.master)

    def test_init_and_decode(self):
        self.assertIn("ELM327", self.elm.init())
        sup = set()
        for base in (0x00, 0x20, 0x40):
            sup |= obd.supported_from(base, self.elm.query("01%02X" % base))
        self.assertEqual(sup, SUPPORTED)

        got = {}
        for pid in (0x0D, 0x0C, 0x05, 0x5B, 0x42, 0x01, 0x11):
            got.update(obd.decode(pid, self.elm.query("01%02X" % pid)))
        self.assertEqual(got["speed"], 60)
        self.assertEqual(got["rpm"], 1726.0)
        self.assertEqual(got["coolant"], 83)
        self.assertAlmostEqual(got["soc"], 60.39, places=1)
        self.assertAlmostEqual(got["volt"], 14.14, places=2)
        self.assertTrue(got["mil"])
        self.assertEqual(got["dtc_count"], 2)
        self.assertAlmostEqual(got["throttle"], 20.0, places=0)   # parsed past SEARCHING...

    def test_no_data_is_none(self):
        self.elm.init()
        self.assertIsNone(self.elm.query("0146"))

    def test_custom_pid(self):
        data = self.elm.query("221234")
        self.assertEqual(obd.custom_value({"start": 0, "bytes": 2, "scale": 0.1}, data), 50.0)

    def test_refused_never_reaches_adapter(self):
        self.elm.init()
        before = list(self.fake.seen)
        for c in ("04", "14FFFFFF", "2F123403", "3101FF00"):
            with self.assertRaises(obd.RefusedCommand):
                self.elm.query(c)
        time.sleep(0.1)
        self.assertEqual(self.fake.seen, before, "a refused request was written to the adapter")


if __name__ == "__main__":
    unittest.main()


class PollerEndToEnd(unittest.TestCase):
    """The real poll loop from hud.py against the fake adapter."""

    def test_poll_loop(self):
        import hud
        master, slave = os.openpty()
        fake = FakeELM(master)
        fake.start()
        state = hud.State("obd")
        custom = [{"field": "soc_mfr", "request": "221234", "header": "7E4",
                   "start": 0, "bytes": 2, "scale": 0.1}]
        t = threading.Thread(target=hud.run_obd,
                             args=(state, os.ttyname(slave), 38400, custom), daemon=True)
        t.start()
        time.sleep(1.2)
        s = state.snapshot()
        fake.stop = True
        self.assertEqual(s["link"], "ok", s["link"])
        self.assertEqual(s["speed"], 60)
        self.assertEqual(s["rpm"], 1726.0)
        self.assertTrue(s["mil"])
        self.assertEqual(s["soc_mfr"], 50.0)
        self.assertIn("5B", s["supported"])
        # custom PIDs follow the 2 s cadence: once in the first 1.2 s, not every cycle
        self.assertEqual(fake.seen.count("221234"), 1, fake.seen.count("221234"))
        # and nothing but reads and listed adapter commands ever reached the adapter
        for cmd in fake.seen:
            if cmd.startswith("AT"):
                obd.check_at(cmd)
            else:
                obd.check_obd(cmd)
