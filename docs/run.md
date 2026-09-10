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
