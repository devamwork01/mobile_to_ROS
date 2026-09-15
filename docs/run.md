# Run instructions

## 1. Start the laptop receiver + dashboard

```bash
cd laptop
source .venv/bin/activate          # Windows: .venv\Scripts\activate
python -m sensorstream.app         # add --selftest to demo without a phone
```
It prints the dashboard URL and the telemetry UDP port, e.g.:
```
dashboard : http://localhost:8080   (ws :8081)
telemetry : UDP :5005
phone -> point the app at   192.168.1.23:5005
```
Open the dashboard in a browser (works from any device on the LAN: `http://<laptop-ip>:8080`).

**Verify the pipeline with no phone:** run with `--selftest` and watch a rotating synthetic
accelerometer (|a| ≈ 9.8) appear in the Sensors table.

## 2. Find the laptop's LAN IP
- Linux: `ip addr` (or `hostname -I`) → the `192.168.x.x` / `10.x.x.x` address on your Wi-Fi NIC.
- Windows: `ipconfig` → "IPv4 Address" of the Wi-Fi adapter.

## 3. Open the firewall for the telemetry port
- **Linux (ufw):** `sudo ufw allow 5005/udp` (and `8080,8081/tcp` if viewing the dashboard from
  another device).
- **Windows dev box:** the first run pops a Windows Defender prompt — allow on Private networks.

## 4. Stream from the phone
- Put phone and laptop on the **same Wi-Fi** (see troubleshooting if they can't reach each other).
- Open **Sensor Stream**, enter the laptop **IP** and **port** (`5005`), tap **Start streaming**.
- The phone shows live Ax/Ay/Az + rate; the laptop dashboard shows the same values and Hz.

## 5. Coordinate sanity check (Phase 1)
Lay the phone flat, screen up, on a table. You should see about:
```
Ax ≈ 0    Ay ≈ 0    Az ≈ +9.81 m/s²
```
That is the Android accelerometer convention (+Z out of the screen). Full coordinate validation
arrives with the 3D view in Phase 3 (`docs/test-plan.md`).

## 6. Build & install the app on a device (ADB)

The debug APK is side-loaded over USB. One-time phone setup: enable **Developer options**
(Settings → About phone → Software information → tap **Build number** 7×), turn on **USB
debugging**, plug in, and accept the "Allow USB debugging?" prompt.

```bash
# Build the APK (JBR 21 — Android Studio's bundled Java 25 breaks Gradle 8.9)
cd android
JAVA_HOME="/c/Users/devam/.jdks/jbr-21.0.11" ./gradlew assembleDebug
#   -> app/build/outputs/apk/debug/app-debug.apk

# adb lives here on this PC:
ADB="/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe"

# List attached devices (copy the serial from the left column)
"$ADB" devices

# Install / upgrade (-r keeps app data). Use -s <serial> when >1 device is plugged in.
"$ADB" -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk   # expect: Success
```

Known device serials: **Galaxy S25 Ultra** (`SM-S938B`) = `RZGL31H3N4V` ·
**Galaxy M36 5G** (`SM-M366B`) = `RZGL50Z0GYJ`.

## 7. Verify on-phone recording (Phase 1)

On-phone recording turns on automatically when streaming starts (screen-off data integrity;
the laptop stays authoritative). The phone status card shows a line like:
`on-phone rec  1.7 MB  ·  buffered 75s  ·  pruned 0`.

```bash
ADB="/c/Users/devam/AppData/Local/Android/Sdk/platform-tools/adb.exe"; D=<serial>

# Segments accumulate under app-private storage, rotating every ~10 s with a monotonic salt:
"$ADB" -s $D exec-out run-as com.sensorstream sh -c 'ls -la files/onphone'
# seg_<ts>_0.ssbin, seg_<ts>_1.ssbin, …  (byte-compatible with laptop/sensorstream/logging_sink.py)

# Total on-disk size (must stay bounded by the caps: 150 MB / 20 min, whichever first):
"$ADB" -s $D exec-out run-as com.sensorstream sh -c 'du -sk files/onphone'

# Reconnect test: kill+restart the laptop server while streaming. The salt series must CONTINUE
# (e.g. 12 -> 13), old segments stay put, storage stays bounded — the recorder is reused across
# reconnects, not rebuilt (regression guard for the orphaned-segments bug).
```

> **Both phone and laptop must be on the same Wi-Fi LAN.** If the phone can't reach the laptop,
> check that the phone's Wi-Fi (client) is on — a phone running a **Mobile Hotspot** with Wi-Fi
> off is on a different subnet and cannot reach the laptop. Verify with
> `"$ADB" -s $D shell ping -c2 <laptop-ip>` (0% loss = reachable).
