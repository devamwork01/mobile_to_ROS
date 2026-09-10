# Coordinate systems (deliverable #8)

The 3D view must never silently swap axes. This documents every frame and the
single, explicit basis change between them. The mapping is unit-tested
(`laptop/webtests/orient.test.js`) and validated physically (see the tests below).

## Frames

**Device / body frame (D)** — phone held upright, screen toward you:
```
        +Y (top)
         |
         |
   +X ---+   ( +Z points OUT of the screen, toward you )
 (right)
```
Right-handed: **+X = right, +Y = top, +Z = out of the screen**. This is the
Android `SensorEvent` convention; accelerometer, gyroscope, magnetometer, gravity
and linear-acceleration are all reported in D.

**World frame (W) = ENU** — from `TYPE_ROTATION_VECTOR` / `getRotationMatrix`:
**+X = East, +Y = North, +Z = Up** (right-handed). The rotation-vector quaternion
`q` represents the rotation **device → world** (`v_world = q · v_device · q⁻¹`).

**Render frame (T) = Three.js** — **+X = right, +Y = up, +Z = toward the viewer**
(right-handed, Y-up).

## The one basis change: ENU → Three

ENU is Z-up; Three is Y-up. The mapping is a single fixed rotation
**B = Rₓ(−90°)**:

| ENU axis | direction in Three |
|---|---|
| East (+X) | **+X** (right) |
| North (+Y) | **−Z** (into the screen) |
| Up (+Z) | **+Y** (up) |

The 3D phone's orientation is therefore `q_mesh = B · q_android`. The phone mesh's
local axes are labeled X/Y/Z to match the device frame; the world arrows are
labeled E/N/U. Implemented in `web/orient.js` (`androidToThree`) and applied in
`web/phone3d.js`.

Euler display uses Android's `getOrientation` convention
(azimuth/pitch/roll from the device→world matrix); heading = azimuth mod 360°.

## Validation (run on the S25 Ultra and M36)

Enable **Rotation vector** (+ accelerometer/gravity) and watch the 3D panel:

1. **Flat on table, screen up** → phone lies flat in the view; the **gravity
   arrow points down (−Up)**; accelerometer reads `Az ≈ +9.81`.
2. **Rotate 90° about each device axis** → the phone in the view turns about the
   *same* axis, the *same* direction (no mirror/swap). Tilt top-down = pitch,
   tilt side = roll.
3. **Yaw (spin flat)** → the heading readout tracks compass heading; the phone's
   +Y (top) swings between the **E/N/U** world arrows correctly.
4. **Gyroscope** → rotating about a device axis makes that axis's ω dominate,
   with the right-hand-rule sign.
5. **Magnetometer** → enable the Mag vector; `|B|` stays ≈ 25–65 µT and the vector
   swings coherently as the phone turns relative to Earth's field.

If any of these disagree with this document, the code is wrong — not the document.
