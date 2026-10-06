/*
 * V.T.D. HUD -- display for a combiner-lens head-up display.
 *
 * Two facts shape everything here:
 *   - On a combiner, black is clear. The page is black and nothing is ever filled behind
 *     content: no panels, no wells, no gradients. Only light carries information.
 *   - It is read in a glance through a windscreen. The normal state is quiet; a value that
 *     is fine (coolant at temperature, battery charging) is not shown at all. Exceptions
 *     arrive large and coloured.
 *
 * URL parameters:
 *   flip=none|x|y|xy   mirror for the lens; find the right one with cal=1
 *   cal=1              calibration card: an "F", arrows, the frame edge
 *   dim=auto|0.2..1    brightness; auto dims between 18:30 and 06:00
 *   demo=1             built-in simulated drive, no backend (automatic on file:// and in
 *                      the single-file bundle, which sets window.HUD_DEMO)
 *   preset=...         fixed values for screenshots: ev, hybrid, regen, warn, lost
 */
(function () {
  "use strict";

  var P = {};
  location.search.replace(/^\?/, "").split("&").forEach(function (kv) {
    if (!kv) return;
    var i = kv.indexOf("=");
    P[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = i < 0 ? "1" : decodeURIComponent(kv.slice(i + 1));
  });

  var C = {
    white: "#F2FBFF", cyan: "#3FD2FF", green: "#5CFF9C", amber: "#FFB020",
    red: "#FF4545", dim: "#2A6B80", grey: "#5B6B73"
  };
  var FONT = "Chakra, 'DejaVu Sans Mono', monospace";
  var CJK = "'Noto Sans CJK TC', 'Noto Sans TC', 'Microsoft JhengHei', sans-serif";
  var RPM_MAX = 6500, SWEEP_MS = 1600, STALE_MS = 2500;

  var cv = document.getElementById("hud");
  var g = cv.getContext("2d");
  var W = 0, H = 0, DPR = 1;

  // ------------------------------------------------------------------ data

  var target = {}, shown = {}, lastData = 0, t0 = performance.now();
  var lastSpeed = null, lastSpeedT = 0, decel = 0;

  function take(s) {
    target = s;
    lastData = Date.now();
    if (typeof s.speed === "number") {
      var now = performance.now();
      if (lastSpeed !== null && now > lastSpeedT) {
        var a = (s.speed - lastSpeed) / ((now - lastSpeedT) / 1000);
        decel += (a - decel) * 0.3;          // km/h per second, smoothed
      }
      lastSpeed = s.speed;
      lastSpeedT = now;
    }
  }

  function has(k) { return typeof target[k] === "number"; }
  function val(k) { return typeof shown[k] === "number" ? shown[k] : 0; }

  function ease(dt) {
    ["speed", "rpm", "throttle", "soc", "coolant", "volt", "load"].forEach(function (k) {
      if (typeof target[k] !== "number") { delete shown[k]; return; }
      if (typeof shown[k] !== "number") { shown[k] = target[k]; return; }
      var rate = k === "soc" || k === "coolant" ? 2 : 10;
      shown[k] += (target[k] - shown[k]) * Math.min(1, dt * rate);
    });
  }

  // ------------------------------------------------------------------ sources

  function demo() {
    // The same shapes as sim.py: creeps away in EV, engine joins under load, regen on braking.
    // The first leg pulls away at once, so whoever opens the demo sees it move.
    var d = { t: 0, v: 0, tgt: 60, hold: 9, soc: 55, cool: 34, rpm: 0, seed: 7 };
    function rnd() { d.seed = (d.seed * 16807) % 2147483647; return d.seed / 2147483647; }
    var last = performance.now();
    setInterval(function () {
      var now = performance.now(), dt = (now - last) / 1000;
      last = now;
      d.hold -= dt;
      if (d.hold <= 0) { d.tgt = [0, 0, 30, 45, 60, 80, 100][Math.floor(rnd() * 7)]; d.hold = 6 + rnd() * 8; }
      var acc = Math.max(-9, Math.min(5, (d.tgt - d.v) * 0.6));
      d.v = Math.max(0, d.v + acc * dt);
      var thr = acc > -0.5 ? Math.max(0, Math.min(100, acc * 14 + d.v * 0.25)) : 0;
      var engine = thr > 45 || d.v > 85 || d.soc < 35;
      var want = engine ? 1100 + d.v * 18 + thr * 22 : 0;
      d.rpm += (want - d.rpm) * Math.min(1, dt * 4);
      if (d.rpm < 300 && !engine) d.rpm = 0;
      if (acc < -1) d.soc += -acc * dt * 0.05;
      else if (engine) d.soc += dt * 0.08;
      else if (d.v > 1) d.soc -= dt * (0.04 + thr * 0.002);
      d.soc = Math.max(20, Math.min(80, d.soc));
      d.cool += (88 - d.cool) * dt * (engine ? 0.02 : 0.004);
      take({ src: "demo", link: "ok", speed: Math.round(d.v), rpm: Math.round(d.rpm), throttle: thr,
             soc: d.soc, coolant: Math.round(d.cool), volt: 14.1, mil: false, dtc_count: 0 });
    }, 100);
  }

  var PRESETS = {
    ev:     { speed: 42,  rpm: 0,    throttle: 12, soc: 63, coolant: 86, volt: 14.2, mil: false },
    hybrid: { speed: 96,  rpm: 3150, throttle: 58, soc: 51, coolant: 91, volt: 14.3, mil: false },
    regen:  { speed: 58,  rpm: 0,    throttle: 0,  soc: 66, coolant: 88, volt: 14.2, mil: false, _decel: -6 },
    warn:   { speed: 27,  rpm: 1450, throttle: 20, soc: 31, coolant: 109, volt: 11.6, mil: true, dtc_count: 2 },
    cold:   { speed: 0,   rpm: 1200, throttle: 0,  soc: 48, coolant: 41, volt: 14.4, mil: false },
    lost:   null
  };

  if (P.preset && P.preset in PRESETS) {
    var p = PRESETS[P.preset];
    if (p) {
      take(Object.assign({ src: "preset", link: "ok" }, p));
      decel = p._decel || 0;
      shown = Object.assign({}, p);
      setInterval(function () { lastData = Date.now(); }, 500);   // a still frame is not stale
    } else {
      take({ src: "preset", link: "error: adapter not found" });
      lastData = 0;
    }
    t0 = -1e9;                                                   // no launch sweep in stills
  } else if (P.demo === "1" || window.HUD_DEMO || location.protocol === "file:" || !window.EventSource) {
    demo();
  } else {
    new EventSource("events").onmessage = function (e) {
      try { take(JSON.parse(e.data)); } catch (err) { /* keep the last good frame */ }
    };
  }

  // ------------------------------------------------------------------ helpers

  function alpha(hex, a) {
    var n = parseInt(hex.slice(1), 16);
    return "rgba(" + (n >> 16) + "," + ((n >> 8) & 255) + "," + (n & 255) + "," + a + ")";
  }

  // Glow is a soft halo under bright strokes: on a combiner it reads as light, not as blur.
  function glow(color, blur) { g.shadowColor = color; g.shadowBlur = blur; }
  function noGlow() { g.shadowBlur = 0; }

  function text(s, x, y, size, color, align, font, weight) {
    g.font = (weight || "600") + " " + size + "px " + (font || FONT);
    g.textAlign = align || "center";
    g.textBaseline = "alphabetic";
    g.fillStyle = color;
    g.fillText(s, x, y);
  }

  // Digits drawn in fixed cells so a proportional face does not jitter as values change.
  function digits(s, cx, y, size, color, glowPx) {
    g.font = "700 " + size + "px " + FONT;
    g.textAlign = "center";
    g.textBaseline = "alphabetic";
    var cell = g.measureText("8").width * 1.02;
    var x = cx - (s.length * cell) / 2 + cell / 2;
    if (glowPx) glow(alpha(color, 0.55), glowPx);
    g.fillStyle = color;
    for (var i = 0; i < s.length; i++) g.fillText(s[i], x + i * cell, y);
    noGlow();
  }

  // ------------------------------------------------------------------ layout

  function resize() {
    DPR = window.devicePixelRatio || 1;
    W = window.innerWidth; H = window.innerHeight;
    cv.width = Math.round(W * DPR); cv.height = Math.round(H * DPR);
    cv.style.width = W + "px"; cv.style.height = H + "px";
  }
  window.addEventListener("resize", resize);
  resize();

  function brightness() {
    if (P.dim && P.dim !== "auto") return Math.max(0.15, Math.min(1, parseFloat(P.dim) || 1));
    if (P.dim === "auto") {
      var d = new Date(), m = d.getHours() * 60 + d.getMinutes();
      return (m >= 18 * 60 + 30 || m < 6 * 60) ? 0.45 : 1;
    }
    return 1;
  }

  // ------------------------------------------------------------------ frame

  var lastFrame = performance.now(), lastPaint = 0;

  function frame() {
    requestAnimationFrame(frame);
    // One clock for everything. The rAF timestamp is not guaranteed to share an origin with
    // performance.now(), and the launch sweep compares against t0 from the latter; mixing the
    // two left the sweep stuck part-way, pinning SOC and rpm to its value.
    var now = performance.now();
    if (now - lastPaint < 1000 / 30) return;   // 30 fps is plenty, and the Pi runs cooler
    lastPaint = now;
    var dt = Math.min(0.25, (now - lastFrame) / 1000);
    lastFrame = now;
    ease(dt);

    g.setTransform(1, 0, 0, 1, 0, 0);
    g.globalAlpha = 1;
    g.fillStyle = "#000";
    g.fillRect(0, 0, cv.width, cv.height);

    var fx = P.flip === "x" || P.flip === "xy" ? -1 : 1;
    var fy = P.flip === "y" || P.flip === "xy" ? -1 : 1;
    g.setTransform(DPR * fx, 0, 0, DPR * fy, fx < 0 ? cv.width : 0, fy < 0 ? cv.height : 0);
    g.globalAlpha = brightness();

    if (P.cal === "1") { calibration(); return; }

    var live = target.link === "ok" && Date.now() - lastData < STALE_MS;
    var sweep = (now - t0) < SWEEP_MS ? Math.sin((now - t0) / SWEEP_MS * Math.PI) : 0;

    // Wide strips put the three blocks side by side. Anything near 16:9 -- the usual
    // 5-7 inch screen under a combiner -- stacks them: speed across the top, mode and
    // engine sharing the row beneath. Squeezing three columns into 800 px made all of it
    // small and left half the screen empty.
    var top = H * 0.14;                          // the alert line owns this band
    if (W / H >= 2.2) {
      var side = W * 0.30;
      center({ x: side, y: top, w: W - side * 2, h: H - top }, live);
      left({ x: 0, y: top, w: side, h: H - top }, live, sweep);
      right({ x: W - side, y: top, w: side, h: H - top }, live, sweep);
    } else {
      var split = top + (H - top) * 0.60;
      center({ x: 0, y: top, w: W, h: split - top }, live);
      left({ x: 0, y: split, w: W / 2, h: H - split }, live, sweep);
      right({ x: W / 2, y: split, w: W / 2, h: H - split }, live, sweep);
    }
    alerts(live);
  }

  // Speed, and nothing else competes with it.
  function center(b, live) {
    var cx = b.x + b.w / 2;
    var size = Math.min(b.h * 0.74, b.w / 1.95);
    var y = b.y + b.h * 0.42 + size * 0.36;
    var s = live && has("speed") ? String(Math.round(val("speed"))) : "--";
    digits(s, cx, y, size, live ? C.white : C.grey, live ? size * 0.12 : 0);
    text("km/h", cx, y + size * 0.24, size * 0.14, live ? C.cyan : C.grey, "center", FONT, "500");
  }

  // Hybrid state. SOC from OBD PID 5B when the car answers it; drive mode inferred.
  function mode(live) {
    if (!live || !has("speed")) return null;
    var rpm = val("rpm"), v = val("speed");
    if (has("rpm") && rpm < 200 && v > 3 && val("throttle") < 1 && decel < -1.5)
      return { label: "REGEN", zh: "回充", color: C.green };
    if (has("rpm") && rpm < 200 && v > 0.5) return { label: "EV", zh: "電動", color: C.green };
    if (has("rpm") && rpm >= 200) return { label: "HYBRID", zh: "油電", color: C.cyan };
    return { label: "READY", zh: "", color: C.dim };
  }

  function left(b, live, sweep) {
    var m = mode(live);
    var cx = b.x + b.w / 2;
    var u = Math.min(b.h, b.w * 0.9);
    var hasSoc = live && has("soc") || sweep > 0;
    var my = b.y + b.h * (hasSoc ? 0.40 : 0.55);
    if (m) {
      glow(alpha(m.color, 0.6), u * 0.06);
      text(m.label, cx, my, u * 0.22, m.color, "center", FONT, "700");
      noGlow();
      if (m.zh) text(m.zh, cx, my + u * 0.14, u * 0.09, alpha(m.color, 0.8), "center", CJK, "500");
    } else {
      text("OBD ---", cx, my, u * 0.14, C.grey);
    }

    // Battery: a thin horizontal gauge. Absent entirely when the car does not answer PID 5B.
    if (hasSoc) {
      var soc = sweep > 0 ? sweep * 100 : val("soc");
      var bw = b.w * 0.64, bh = Math.max(5, u * 0.045), bx = cx - bw / 2, by = b.y + b.h * 0.80;
      var low = soc < 35 && !sweep;
      var col = low ? C.amber : C.green;
      g.strokeStyle = alpha(col, 0.55);
      g.lineWidth = 1.5;
      g.strokeRect(bx, by, bw, bh);
      glow(alpha(col, 0.7), u * 0.04);
      g.fillStyle = col;
      g.fillRect(bx + 2, by + 2, Math.max(0, (bw - 4) * soc / 100), bh - 4);
      noGlow();
      text(Math.round(soc) + "%", bx + bw, by - u * 0.04, u * 0.09, col, "right");
      text("SOC", bx, by - u * 0.04, u * 0.07, alpha(col, 0.75), "left", FONT, "500");
    }
  }

  // Engine: an arc that only exists when the engine is turning.
  function right(b, live, sweep) {
    var cx = b.x + b.w / 2;
    var u = Math.min(b.h * 1.15, b.w * 0.9);
    var rpm = sweep > 0 ? sweep * RPM_MAX : (live && has("rpm") ? val("rpm") : 0);
    var r = Math.min(u * 0.36, b.h * 0.42), cy = b.y + b.h * 0.52;
    var a0 = Math.PI * 0.80, a1 = Math.PI * 2.20;
    var frac = Math.max(0, Math.min(1, rpm / RPM_MAX));
    var red = rpm > 5800;

    g.lineCap = "round";
    g.lineWidth = Math.max(3, u * 0.025);
    g.strokeStyle = alpha(C.cyan, 0.18);                 // the track: faint, so it never shouts
    g.beginPath(); g.arc(cx, cy, r, a0, a1); g.stroke();
    if (frac > 0.003) {
      glow(alpha(red ? C.red : C.cyan, 0.7), u * 0.05);
      g.strokeStyle = red ? C.red : C.cyan;
      g.lineWidth = Math.max(4, u * 0.04);
      g.beginPath(); g.arc(cx, cy, r, a0, a0 + (a1 - a0) * frac); g.stroke();
      noGlow();
    }
    g.lineCap = "butt";

    if (!live && !sweep) { text("--", cx, cy + u * 0.06, u * 0.16, C.grey); return; }
    if (rpm < 200 && !sweep) {
      text("ENGINE", cx, cy - u * 0.005, u * 0.075, C.dim, "center", FONT, "600");
      text("OFF", cx, cy + u * 0.085, u * 0.075, C.dim, "center", FONT, "600");
    } else {
      digits(String(Math.round(rpm / 10) * 10), cx, cy + u * 0.07, u * 0.17, red ? C.red : C.white, u * 0.03);
      text("rpm", cx, cy + u * 0.17, u * 0.06, C.cyan, "center", FONT, "500");
    }
  }

  // Exceptions only. A fine value earns no pixels.
  function alerts(live) {
    var list = [];
    if (!live) list.push({ t: target.link && target.link !== "ok" ? "OBD 未連線" : "OBD 等待中", c: C.grey });
    if (live && target.mil) list.push({ t: "引擎故障燈" + (target.dtc_count ? "  " + target.dtc_count : ""), c: C.amber });
    if (live && has("coolant") && val("coolant") >= 105) list.push({ t: "水溫 " + Math.round(val("coolant")) + "°C", c: C.red });
    else if (live && has("coolant") && val("coolant") < 60) list.push({ t: "暖車中 " + Math.round(val("coolant")) + "°C", c: C.amber });
    if (live && has("volt") && val("volt") > 1 && val("volt") < 12.0) list.push({ t: "電壓 " + val("volt").toFixed(1) + "V", c: C.amber });
    if (!list.length) return;

    // A slow pulse, never below 70 %: a warning that fades out is a warning missed.
    var pulse = 0.85 + 0.15 * Math.sin(performance.now() / 260);
    var n = list.length, slot = W / Math.max(n, 2);
    var size = Math.min(H * 0.08, slot / 7.5);
    var y = H * 0.07;
    list.forEach(function (a, i) {
      var urgent = a.c === C.red;
      var x = n === 1 ? W / 2 : slot * (i + 0.5) + (W - slot * n) / 2;
      g.globalAlpha = brightness() * (urgent ? pulse : 1);
      glow(alpha(a.c, 0.6), size * 0.6);
      text(a.t, x, y + size * 0.4, size, a.c, "center", CJK, "700");
      noGlow();
    });
    g.globalAlpha = brightness();
  }

  // For finding the right flip in the car: the F must read as an F through the lens.
  function calibration() {
    var u = Math.min(W, H);
    g.strokeStyle = C.cyan; g.lineWidth = 4;
    g.strokeRect(6, 6, W - 12, H - 12);
    text("F", W / 2, H / 2 + u * 0.22, u * 0.62, C.white, "center", FONT, "700");
    text("TOP ↑ 上", W / 2, u * 0.12, u * 0.08, C.green, "center", CJK, "700");
    text("← LEFT 左", u * 0.06, H / 2, u * 0.07, C.amber, "left", CJK, "700");
    text("flip=" + (P.flip || "none"), W - u * 0.06, H - u * 0.06, u * 0.06, C.cyan, "right");
  }

  var go = function () { requestAnimationFrame(frame); };
  if (document.fonts && document.fonts.load) {
    document.fonts.load("700 40px Chakra").then(go, go);
  } else {
    go();
  }
})();
