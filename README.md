# O.R.I.O.N.

<img src="docs/icon.png" width="88" align="right" alt="O.R.I.O.N. icon" />

A **dongle-free vehicle gauge dashboard** that runs on the factory infotainment screen of the
**Infiniti InTouch** head unit in the **V37 Q50 / Q60** — reading the car's own CAN-bus signals
(RPM, oil temp, **oil pressure**, coolant, per-wheel TPMS, G-forces, gear, throttle, power) with
**no OBD dongle, no Bluetooth, no phone**.

![dashboard running on the head unit](docs/dashboard.jpg)

*(above: live idle data on the unit it was developed on — "42 CAN signals live")*

## ⚠️ Compatibility — read this first

**Target:** the **V37 Infiniti Q50 / Q60** whose InTouch head unit runs the **Android 2.3.x**
revision this was reverse-engineered from (a customized **Android 2.3.7, API 10, x86** unit).
Developed and verified on a **2018 Q60 Red Sport 400 AWD (V37, VR30DDTT)**; the bundled public OBU
cert and the sensor-type → unit calibration both come from that car's firmware.

**It is not guaranteed across every Q50/Q60.** Other model years, regions, and later InTouch
hardware/software ship different firmware (different OS/SoC, different CAN maps) — the app may not
load, or the sensor numbers/scalings may be off. If your unit isn't that **Android 2.3.x InTouch**,
treat this as **unverified**: only run it on a matching unit, on **a vehicle you own**, and
sanity-check the scalings against your own gauges.

## How it works

Under its Linux nav UI, this InTouch unit runs a customized **Android 2.3.7 (API 10, x86)** that
hosts the "App Garage." Two things make this app possible:

1. **It exposes the vehicle CAN bus as standard Android `Sensor`s** (`VS_ID_*`, vendor "Ygomi",
   sensor types 12–53). Any app reads them with plain `SensorManager.getDefaultSensor(type)` +
   `registerListener` → `onSensorChanged(e).values[0]`. Reading values needs
   `com.ygomi.permission.IVI_CAN_READ`, which is a *dangerous*-level permission → auto-granted to a
   normal self-signed app on Android 2.3.
2. **`ivi.isDistractive="false"`** in the manifest sets `runningRestriction=0`, so the app stays
   **usable while driving** (the platform defaults unmarked apps to restricted).

Pure-Java, ships no native code (runs on the x86 CPU), needs no Google services, works fully offline.

## Signals & calibration

Raw sensor values are unlabeled floats; these were calibrated against the VR30DDTT they were read from:

| Gauge | Sensor | Scaling | Notes |
|---|---|---|---|
| RPM | 13 | direct | warm idle ~650 (cold/warmup higher) |
| Coolant / Oil temp | 14 / 15 | direct °C | |
| **Oil pressure** | 16 | raw × 145 → **psi** | ~22 psi warm idle (raw is MPa) |
| Speed | 17 | raw × 0.621 → **mph** | raw is km/h |
| Torque | 12 | ≈ Nm | |
| Power | 32 | raw × 1.047e-4 → **kW** | raw = rpm × torque |
| TPMS ×4 | 36–39 | direct **psi** | |
| Throttle | 23 | % | |
| Gear | 22 | enum | P=1, R=2, N=3, D=4, M1–M7=16–22 (confirmed on-car) |
| G-force lat/long | 20 / 21 | raw | full-scale ≈ 1 g assumed |

These mappings are specific to the firmware above — treat them as a starting point on any other unit.
Constants live at the top of [`GaugeView.java`](src/com/appgarage/dash/GaugeView.java).

**Not available** (not on this unit's CAN): boost/MAP, AFR/lambda, knock, ignition timing — those
are ECU-tuning parameters only tools like EcuTek read. This is instrument-cluster-grade data.

## Build

Requirements: a JDK, the Android SDK command-line tools (`build-tools;34.0.0` + `platforms;android-34`),
and Python 3 with `cryptography` (`pip install cryptography`, used by the `.epk` step). Point the build
at your toolchain via the `JAVA_HOME` and `ANDROID_SDK` env vars (or edit the two lines atop `build.sh`).

```
git clone https://github.com/bugjosh/appgarage-dash && cd appgarage-dash
bash build.sh          # -> build/dash.apk  +  build/dash.epk  (loadable via App Garage)
```
The APK is `minSdkVersion 10`, pure-Dalvik (no `lib/`), self-signed with a persistent `keystore.ks`.

## Loading onto the head unit

The head unit's **App Garage** installs USB apps only as `.epk` packages. `build.sh` produces
`build/dash.epk` for you, using the **public** OBU certificate in [`keys/obu_cert.pem`](keys/README.md)
(only the public half is shipped — the private key and firmware are not).

**On a computer**
1. Format a USB stick as **FAT32**.
2. Copy **`build/dash.epk`** to the **root** of the stick (App Garage scans the stick for `.epk` files).

**In the car** (engine running, so the gauges show live data)

3. Insert the stick into the head unit's USB port. If it prompts *"USB music device detected… create or
   replace voice recognition data?"*, choose **No**.
4. Give the unit a moment after start-up — it can take **up to a minute** to finish loading, showing
   **"Loading all apps"** (or similar) along the bottom of the main screen. Wait until that clears.
5. From the main screen, press the **right arrow once** — the **App Garage** icon is there. Open it.
6. Choose **Install Apps via USB**. It lists the apps found on the stick — select **O.R.I.O.N.**
   (or **Install All Apps**), then confirm **Install this app?** → **Install**.
7. Wait for **Installation from USB complete** / **Apps installed** — don't pull the USB mid-install
   (*"Please do not remove the USB during installation"*).
8. Launch **O.R.I.O.N.** from App Garage (or its home-screen shortcut). The gauges go live.

**Updating:** App Garage hides an install candidate whose `versionCode` is ≤ the one already
installed, so each `build.sh` run stamps a higher `versionCode` (unix time). A new `dash.epk` will
then appear and install over the old app **provided it's signed with the same key** (`keystore.ks`).
If it doesn't show in the list, or you rebuilt with a different key — a fresh clone makes its own,
and the released `.epk` differs from a self-built one — **uninstall the existing "O.R.I.O.N."
first**, then install.

> The exact menu wording above is taken from the App Garage firmware; your unit's labels should match
> or be close. The bundled cert works on units running the matching InTouch firmware; units with
> different firmware supply their own (see [`keys/README.md`](keys/README.md)). Only load software onto
> **a vehicle you own.**

## Credits

This wouldn't exist without the groundwork of others:

- **Firmware image:** the Q50/Q60 system image that made the platform legible was dumped and shared
  by **@tdpequinox** (on the **DCUFix** Discord) — thank you.
- **App Garage load format:** the on-device package format and how apps are signed/accepted was
  worked out from that firmware.

## Disclaimer

For use on **your own vehicle**, at your own risk. Don't let an on-screen display distract you from
driving. Not affiliated with Infiniti, Nissan, Ygomi, or Airbiquity; ships no proprietary keys,
firmware, or copyrighted assets.

## License

MIT — see [LICENSE](LICENSE).
