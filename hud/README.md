# V.T.D. HUD

A head-up display for the Q50 3.5 Hybrid that adds what the factory screens never show:
engine speed, the hybrid drive mode, and — where the car answers for it — battery charge.

It is a **separate, add-on system**. The factory InTouch unit and the V.T.D. app on it are
untouched, and the whole thing comes out by unplugging it.

```
OBD-II adapter ──USB──> Raspberry Pi 5 (hud.py) ──HDMI──> bright screen ──> combiner lens
```

## Read-only, and what that does and does not mean

Every request to the car goes through one function that refuses anything outside the read
services (`01 03 07 09 0A 22`) **before a byte is written**. Clearing trouble codes, actuator
tests, routine control, security access and writes are not switched off — there is no code path
to them, and a config file asking for one makes `hud.py` refuse to start. The tests check that a
refused request never reaches the adapter.

Unlike the V.T.D. head-unit app, which only listens, an OBD reader **asks**: each query is a
frame on the bus. Standard mode 01 reads are what every OBD app sends while driving, and the
poll rate here is kept modest (fast values every cycle, slow ones every 2 s, status every 15 s).
Try it parked first.

## See it before buying anything

```
python3 tools/bundle_demo.py hud-demo.html     # one self-contained file; open it in any browser
python3 hud.py --sim                           # or the full pipeline with a simulated drive
                                               #   -> http://127.0.0.1:8080/
```

Fixed frames for judging layout: `?preset=ev|hybrid|regen|warn|cold|lost`.

## What it shows

| | |
|---|---|
| **Speed** | large, centre |
| **Drive mode** | `EV` (moving, engine stopped) · `HYBRID` (engine turning) · `REGEN` (engine stopped, throttle closed, slowing) · `READY`. Inferred from OBD rpm, speed and throttle; the car does not report it directly |
| **Engine** | an arc that only lights when the engine turns; `ENGINE OFF` otherwise |
| **Battery** | **only if the car answers PID `5B`** (hybrid pack remaining life). If it does not, the gauge is simply absent; see *Manufacturer PIDs* |
| **Warnings** | check-engine light and code count, coolant hot or still cold, low voltage, lost OBD link. Shown only when they apply |

A combiner shows black as clear, so the page is black and draws only light: no panels, no
fills behind content. A value that is fine earns no pixels.

## Hardware

| Part | Notes |
|---|---|
| Raspberry Pi 5 (4 GB) + case | 20–25 s from power to picture |
| OBD-II adapter, **USB** | an OBDLink SX, or another genuine ELM327. Avoid the cheap "v2.1" clones: they lie about supported commands. Bluetooth Classic works too via `rfcomm` |
| **High-brightness** HDMI screen | a combiner reflects a small fraction of the light. Aim for **≥ 1000 nits**; a 300–500 nit PC-case screen will be readable at night and washed out in daylight |
| Combiner lens / HUD film | the glass the image reflects off |
| 12 V → 5 V 5 A converter | on an **ignition-switched (ACC)** fuse tap, so it powers down with the car |

**Placement:** keep the screen and the lens out of any airbag deployment zone, and out of the
line of sight to the road. Show driving information only — never video while moving.

## Install on the Pi (Raspberry Pi OS Bookworm)

```
scp -r hud pi@<pi>:/home/pi/hud
sudo cp /home/pi/hud/pi/hud.service /etc/systemd/system/
sudo systemctl enable --now hud                         # backend; edit --port first
echo "/home/pi/hud/pi/kiosk.sh &" >> ~/.config/labwc/autostart
sudo raspi-config   # Performance Options -> Overlay File System -> enable
```

The overlay filesystem makes the SD card read-only, so cutting the power with the ignition is
safe. Turn it off again before changing anything.

Prefer `/dev/serial/by-id/...` over `/dev/ttyUSB0` in the service file: it survives the adapter
being plugged into a different socket.

## Calibrate the mirror (once, in the car)

The lens flips the image, and which way depends on how the screen lies. Start with
`HUD_URL_EXTRA="cal=1"` and try `FLIP=none`, `x`, `y`, `xy` in `kiosk.sh` until the large **F**
reads correctly through the lens, with **TOP** at the top. Then remove `cal=1`.

`DIM=auto` halves the brightness between 18:30 and 06:00; a fixed `DIM=0.5` also works.

## Manufacturer PIDs

Hybrid values beyond PID `5B` live behind manufacturer requests (service `22`) addressed to a
specific ECU. Copy `config.example.json` to `config.json` and fill in entries **only from a
source you trust or from your own reading with a scan tool** — the example entry is a shape, not
a real Q50 PID. Each entry must be a read; anything else stops the program at startup.

## Tests

```
python3 -m unittest discover -s tests
```

They run the real serial code against a fake ELM327 on a pseudo-terminal, including the full
poll loop, and check that every refused request stays off the wire.
