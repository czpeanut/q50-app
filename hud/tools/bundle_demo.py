#!/usr/bin/env python3
"""Inline the page, the script and the font into one self-running demo file.

    python3 tools/bundle_demo.py out.html

The result needs no server and no backend: it plays the built-in simulated drive. Open it in
any browser to judge the display before buying anything.
"""
import base64
import io
import os
import sys

HERE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")


def main(out):
    page = io.open(os.path.join(HERE, "index.html"), encoding="utf-8").read()
    js = io.open(os.path.join(HERE, "hud.js"), encoding="utf-8").read()
    font = base64.b64encode(open(os.path.join(HERE, "fonts", "ChakraPetch.ttf"), "rb").read()).decode()
    page = page.replace('url("fonts/ChakraPetch.ttf")', 'url("data:font/ttf;base64,%s")' % font)
    page = page.replace('<script src="hud.js"></script>',
                        "<script>window.HUD_DEMO = true;</script>\n<script>\n%s\n</script>" % js)
    assert "data:font/ttf" in page and "HUD_DEMO" in page, "bundle markers missing"
    io.open(out, "w", encoding="utf-8").write(page)
    print("wrote %s (%d KB)" % (out, os.path.getsize(out) // 1024))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "hud-demo.html")
