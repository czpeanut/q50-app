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

The closest substitute is **type 26 `VS_ID_REGENERATION`**, which is the only powertrain-energy
signal present. Whether it is actually fed on this car is not answerable from the inventory —
that needs live values, which is what `SensorWatchActivity` exists to find out.

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
| 12 | `VS_ID_EFFECTIVE_TORQUE` | 879.0 | 1.0 | placeholder max; ~Nm on VR30 |
| 13 | `VS_ID_ENGINE_RPM` | 879.0 | 1.0 | placeholder max; reads 0 when the engine is off in READY |
| 14 | `VS_ID_ENGINE_COOLANT_TEMPERATURE` | 214.0 | 1.0 | °C direct |
| 15 | `VS_ID_ENGINE_OIL_TEMPERATURE` | 205.0 | 1.0 | reported −50 at rest on this car |
| 16 | `VS_ID_ENGINE_OIL_PRESSURE` | 879.0 | 1.0 | placeholder max; MPa on VR30 |
| 17 | `VS_ID_VEHICLE_SPEED` | 655340.0 | 1.0 | **scaling suspect** — range far exceeds km/h |
| 18 | `VS_ID_DISTANCE_TO_EMPTY` | 6553400.0 | 1.0 | not in the original notes |
| 19 | `VS_ID_FUEL_CONSUMPTION_FINE` | 2048000.0 | 0.001 | fine-grained instantaneous |
| 20 | `VS_ID_TRANSVERSAL_ACCELERATION` | 2000.0 | 1.0 | **scaling suspect** — 1 g full-scale was a guess |
| 21 | `VS_ID_LONGITUDINAL_ACCELERATION` | 2000.0 | 1.0 | as above |
| 22 | `VS_ID_GEAR_POSITION` | −0.0 | −0.0 | enum, confirmed on-car |
| 23 | `VS_ID_ACCELERATOR_PEDAL_POSITION` | 1000.0 | 0.001 | **pedal, not throttle** — distinct on a hybrid |
| 24 | `VS_ID_BRAKE_PEDAL_POSITION` | 90.0 | 1.0 | not in the original notes; the control input for regen testing |
| 25 | `VS_ID_STEERING_ANGLE` | 9000.0 | 0.1 | **present** — ±900° at 0.1° |
| 26 | `VS_ID_REGENERATION` | 63500.0 | 1.0 | **the one hybrid-adjacent signal; 16-bit range suggests offset encoding** |
| 27 | `VS_ID_ILLUMI` | −0.0 | −0.0 | enum, day/night illumination |
| 28 | `VS_ID_ECO_MODE` | −0.0 | −0.0 | enum; drive-mode candidate |
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

## Corrections to the original handover notes

- Types run 12–50, not 12–53.
- Type 23 is the **accelerator pedal position**, not throttle opening. On a hybrid these
  diverge: pedal travel with the engine off produces no throttle angle at all.
- Types 36–39 are FR/FL/RR/RL in that order, matching `GaugeView`'s existing constants.
- `maximumRange` cannot be used for calibration (see above).
