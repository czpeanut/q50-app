# Observed sensor inventory — Q50 3.5 Hybrid (V37, VQ35HR)

Read off `SensorListActivity` on the actual car (Taiwan-spec Q50 3.5 Hybrid, InTouch lower
display). This supersedes guesses carried over from the VR30DDTT Q60 the original project was
developed on.

**39 sensors, types 12–50, contiguous with no gaps. Every one has vendor `Ygomi`.** There are
no standard Android sensors (no accelerometer, no gyro) — the whole list is the vehicle bus.

## The headline result: there is no HV battery signal

Nothing in the list carries `BATTERY`, `SOC`, `HV`, `CHARGE`, `MOTOR` or `HYBRID`. The hybrid
system's state of charge is **not exposed through the Android Sensor API** on this unit, even
though the factory InTouch energy-flow screen clearly has the data. It presumably stays in the
Linux/AV layer.

Type 26 `VS_ID_REGENERATION`, the only powertrain-energy signal in the list, turned out to hold
a flat zero across a full drive. Type 13 `ENGINE_RPM` is dead too, which closes the last
indirect route: electric drive cannot be inferred from an engine speed that never reports.

**There is no hybrid state on this bus at all.** The nearest thing the car does publish is type
12 `EFFECTIVE_TORQUE`, which moves with load but says nothing about where the power came from.

## Confirmed on-car (measured, not inferred)

| Type | Result |
|---|---|
| 17 `VEHICLE_SPEED` | **raw value is km/h directly.** The original notes were right; the `max=655340` reading was not a clue to anything. |
| 25 `STEERING_ANGLE` | **raw value is degrees directly**, **signed: right positive, left negative**, roughly **±390 at full lock**, carrying one decimal. Note this is *not* the 0.1-degree unit the `resolution` field implies — 390 raw is 390°, matching the quick DAS rack. Fully calibrated; ready to use as-is. |
| 12 `EFFECTIVE_TORQUE` | **live and wide-ranging** — −400 (rest placeholder) to 1647.5 over one 578 s drive. The only powertrain signal on this car that genuinely moves. Unit unknown, so it is displayed uncalibrated. |
| 26 `REGENERATION` | **flat zero.** Held 0.000 for min, max and current across a full 578 s drive with braking, having previously looked alive only because a constant zero counts events like any other value. Nothing in the Sensor API carries hybrid energy flow. |
| 13 `ENGINE_RPM` | **dead.** Stayed at 0.000 across repeated drives on a warm car, including a whole trip watched by an indicator that would have lit the moment it left zero. It never did. Nothing can be built on it, and the electric-drive inference that depended on it has been removed. |
| 23 `ACCELERATOR_PEDAL_POSITION` | peaked at **39.25** under ordinary throttle, which reads as a percentage despite the declared maximum of 1000. |
| 24 `BRAKE_PEDAL_POSITION` | 0 to **54** observed under normal braking, against a declared maximum of 90. |
| 26 `REGENERATION` (earlier note) | **live.** Delivers events continuously (n rose with every other signal); it simply reads a flat 0.000 while stationary with the engine off. An earlier note recording it as dead was a misreading — a constant zero is not an absent signal. Its behaviour under load is still unknown and is the one open hybrid question. |
| 28 `ECO_MODE` | **dead.** Declared in the inventory and never delivers an event, while every neighbouring signal counts up. |
| 24 `BRAKE_PEDAL_POSITION` | live; 10 to 68 observed over one brake application against a declared max of 90. |
| 20 / 21 G axes | raw value is **g directly** — −0.010 to −0.020 on type 21 at rest on a slight slope. `max=2000` is another placeholder. |

The distinction that matters: `n=` counts events. A signal showing `0.000  n=144` is being fed
and happens to be zero; a signal showing `no data yet` is never fed at all. Only the second is
dead.

## Platform: Android is a guest, not the system

`/proc/mounts` shows two systems at once. The host is a systemd Linux — `aufs` on `/var`,
cgroups, and the navigation application under `/home/naviwork`. Android 2.3 runs inside it,
with its own root on a read-only `tmpfs` and its partitions mounted from `/dev/mmcblk0p5`
(`/system`) and `/dev/mmcblk0p9` (`/data`). App Garage apps are guests of a guest, which is why
so little of the filesystem is reachable.

## Storage: writing works, retrieval is the problem

`getFilesDir()` (`/data/data/com.appgarage.dash/files`) **is writable**. Nothing else found so
far is. On an unrooted Android 2.3 head unit with no file manager and no adb, that directory is
readable only by this app, so a recording written there cannot be carried indoors.

Recording therefore always targets internal storage — guaranteed present, needs no permission,
and cannot disappear mid-drive the way a USB stick pulled from the socket can — and a separate
EXPORT step copies the files out to whatever reachable location exists at that moment. The
probe re-runs on demand, so a stick plugged in after boot (or after the drive) is picked up.

### What the probe actually found, with a stick plugged in

| Path | fs | Verdict |
|---|---|---|
| `/data/data/com.appgarage.dash/files` | ext4 | writable, **persistent**, unreachable from outside the app |
| `/mnt/sdcard` and below | **tmpfs** | writable, **RAM disk** — dies at power-off, a PC can never read it |
| `/data/system/tmp` | **tmpfs** | same |
| `/data/system/tmp/sdb1` | **vfat** | **this is the USB stick.** Mounted `rw`, but with `fmask=0022,dmask=0022` and owned by root, so this app may read it and cannot write to it |
| `/cache`, `/data`, `/data/local/tmp`, `/mnt/asec`, `/mnt/obb` | — | permission denied |

`externalStorageState` reports `removed`: there is no real external volume: `/mnt/sdcard` is
only a tmpfs placeholder wearing the name.

**So there is exactly one persistent writable location — the app's own private directory — and
it cannot be read by anything else on an unrooted unit.** Ranking candidate directories by the
words in their path was wrong for this reason: `/mnt/sdcard` scored highest on the strength of
the word "sdcard" while being RAM. Volatility is now read from the filesystem type.

### The consequence

**The screen is the only output channel this unit has.** The USB stick is readable but not
writable, so it can carry data *in* but not *out*. Recordings are worth keeping in the private
directory for on-device analysis, but any conclusion that has to leave the car has to leave it
through the display and a camera — which is what the min/max latch is for.

## `maximumRange` is only sometimes real

Types **12, 13, 16 and 32 all report `max=879.0`** — a shared placeholder, since 879 rpm is
obviously not the engine's ceiling. Do not calibrate against `max`.

Where it *is* self-consistent it can be trusted: types 36–39 report `max=63.75 res=0.25`, which
is exactly 255 × 0.25, confirming the TPMS raw value is already psi in quarter-psi steps.

`max=-0.0 res=-0.0` (types 22, 27, 28) means the platform filled in nothing, which is normal for
**enumerations** — type 22 `GEAR_POSITION` reports that and is confirmed working on-car
(P/R/N/D = 1/2/3/4, M1–M7 = 16–22). So `-0.0` is not evidence that a signal is dead.

## Full list

| Type | Name | max | res | Notes |
|---|---|---|---|---|
| 12 | `VS_ID_EFFECTIVE_TORQUE` | 879.0 | 1.0 | **live, −400 at rest to 1647.5 driving.** Unit unknown; max field is a placeholder |
| 13 | `VS_ID_ENGINE_RPM` | 879.0 | 1.0 | **dead — 0.000 across repeated warm drives** |
| 14 | `VS_ID_ENGINE_COOLANT_TEMPERATURE` | 214.0 | 1.0 | °C direct |
| 15 | `VS_ID_ENGINE_OIL_TEMPERATURE` | 205.0 | 1.0 | reported −50 at rest on this car |
| 16 | `VS_ID_ENGINE_OIL_PRESSURE` | 879.0 | 1.0 | placeholder max; MPa on VR30 |
| 17 | `VS_ID_VEHICLE_SPEED` | 655340.0 | 1.0 | **confirmed km/h directly** — the max field meant nothing |
| 18 | `VS_ID_DISTANCE_TO_EMPTY` | 6553400.0 | 1.0 | not in the original notes |
| 19 | `VS_ID_FUEL_CONSUMPTION_FINE` | 2048000.0 | 0.001 | fine-grained instantaneous |
| 20 | `VS_ID_TRANSVERSAL_ACCELERATION` | 2000.0 | 1.0 | **raw is g directly**; max is a placeholder |
| 21 | `VS_ID_LONGITUDINAL_ACCELERATION` | 2000.0 | 1.0 | **raw is g directly** (−0.01 at rest on a slope) |
| 22 | `VS_ID_GEAR_POSITION` | −0.0 | −0.0 | enum, confirmed on-car |
| 23 | `VS_ID_ACCELERATOR_PEDAL_POSITION` | 1000.0 | 0.001 | **pedal, not throttle** — distinct on a hybrid |
| 24 | `VS_ID_BRAKE_PEDAL_POSITION` | 90.0 | 1.0 | **live**, 10–68 observed against a declared max of 90 |
| 25 | `VS_ID_STEERING_ANGLE` | 9000.0 | 0.1 | **confirmed: degrees, right +, left −, ±390 full lock, 1 decimal** |
| 26 | `VS_ID_REGENERATION` | 63500.0 | 1.0 | **dead — flat 0.000 across a full drive with braking** |
| 27 | `VS_ID_ILLUMI` | −0.0 | −0.0 | enum, day/night illumination |
| 28 | `VS_ID_ECO_MODE` | −0.0 | −0.0 | **dead — never delivers an event** |
| 29 | `VS_ID_FUEL_CONSUMPTION_HISTORY` | 200000.0 | 1.0 | |
| 30 | `VS_ID_AVERAGE_OF_FUEL_CONSUMPTION` | 99000.0 | 1.0 | |
| 31 | `VS_ID_MOMENT_FUEL_CONSUMPTION` | 200000.0 | 1.0 | should fall to zero under EV drive → engine on/off proxy |
| 32 | `VS_ID_ENGINE_POWER` | 879.0 | 1.0 | placeholder max; rpm×Nm on VR30 |
| 33 | `VS_ID_DISPLAY_STYLE` | 3.0 | 1.0 | enum |
| 34 | `VS_ID_FLAT_TIRE` | 15.0 | 1.0 | bit field (4 wheels) |
| 35 | `VS_ID_LOW_TIRE_PRESSURE` | 15.0 | 1.0 | bit field |
| 36 | `VS_ID_TIRE_PRESSURE_DATA_FR` | 63.75 | 0.25 | psi, 255 × 0.25 |
| 37 | `VS_ID_TIRE_PRESSURE_DATA_FL` | 63.75 | 0.25 | psi |
| 38 | `VS_ID_TIRE_PRESSURE_DATA_RR` | 63.75 | 0.25 | psi |
| 39 | `VS_ID_TIRE_PRESSURE_DATA_RL` | 63.75 | 0.25 | psi |
| 40 | `VS_ID_TRANSMITTER_STATUS` | 15.0 | 1.0 | TPMS sensor health |
| 41 | `VS_ID_SETTING_PRESSURE_FRONT` | 15.0 | 1.0 | target pressure |
| 42 | `VS_ID_SETTING_PRESSURE_REAR` | 15.0 | 1.0 | target pressure |
| 43 | `VS_ID_DISTANCETOTALIZER` | 999999.0 | 1.0 | odometer — **not** rpm |
| 44 | `VS_ID_FUEL_LOW` | 1.0 | 1.0 | boolean |
| 45 | `VS_ID_HAND_BRAKE` | 1.0 | 1.0 | boolean |
| 46 | `VS_ID_SELECT_PRESSURE_SUPPORT` | 1.0 or 3.0 | 1.0 | photos disagree; re-check |
| 47 | `VS_ID_RESET_TPMS` | 3.0 or 1.0 | 1.0 | photos disagree; re-check |
| 48 | `VS_ID_ICCExistance` | 1.0 | 1.0 | adaptive cruise fitted? |
| 49 | `VS_ID_MARKET` | 0.0 | 1.0 | region code |
| 50 | `VS_ID_IOP_ILL` | 1.0 | 0.0 | |

Rows 46/47 differ between two photographs of the same screen; the values above are whichever
the sharper frame showed. Re-read them on the unit before relying on either.

## Gear position while in D

Type 22 publishes the selector position, not the ratio: `P/R/N/D = 1/2/3/4`, with the
individual gears appearing only as `16..22` for `M1..M7` in manual mode. In D it reports a
bare `4` and nothing more, on the reference car.

Whether the hybrid differs is worth one look, which is why type 22 is now on the watcher's
panel: drive in D and see whether the number ever leaves 4. If it does not, the ratio is
simply not published, and the two ways to have it on screen are to use the manual gate or the
paddles (which already display correctly as M1–M7), or to infer it from the rpm-to-speed
ratio. That inference needs type 13, which this car is not reliably publishing, so it is
blocked until the rpm question is settled.

Note the car is a **7-speed**, so the manual range is M1–M7 rather than 1–5.

## Corrections to the original handover notes

- Types run 12–50, not 12–53.
- Type 23 is the **accelerator pedal position**, not throttle opening. On a hybrid these
  diverge: pedal travel with the engine off produces no throttle angle at all.
- Types 36–39 are FR/FL/RR/RL in that order, matching `GaugeView`'s existing constants.
- `maximumRange` cannot be used for calibration (see above). Type 17 settled this: its max
  reads 655340 and its raw value is plain km/h, so the field is not even a scaled bound.
- Type 25's raw value is degrees, not the 0.1-degree unit `resolution` suggests. `resolution`
  describes the precision of the number, not its unit.
