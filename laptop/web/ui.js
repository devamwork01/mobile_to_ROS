// Dashboard rendering: connection status, throughput stats, live per-sensor
// table. Phase 2 adds the control channel — device model + real sensor names
// (from the phone's catalog) + connect/disconnect state. The 3D view (Phase 3)
// and rolling plots (Phase 4) attach to the same SensorNet data stream later.
(function () {
  // Fallback names when no catalog has been received yet (Android Sensor.TYPE_*).
  const TYPE = {
    1: "Accelerometer", 2: "Magnetic field", 3: "Orientation (deprecated)",
    4: "Gyroscope", 5: "Light", 6: "Pressure", 7: "Temperature (deprecated)",
    8: "Proximity", 9: "Gravity", 10: "Linear acceleration", 11: "Rotation vector",
    12: "Relative humidity", 13: "Ambient temperature", 14: "Magnetic field (uncal)",
    15: "Game rotation vector", 16: "Gyroscope (uncal)", 18: "Step detector",
    19: "Step counter", 20: "Geomagnetic rotation vector", 21: "Heart rate",
    28: "Pose 6DOF", 34: "Accelerometer (uncal)",
  };
  const UNITS = { 1: "m/s²", 2: "µT", 4: "rad/s", 5: "lx", 6: "hPa", 8: "cm", 9: "m/s²", 10: "m/s²", 13: "°C", 12: "%" };

  let catalogByHandle = {}; // handle -> {name, units}
  const nameOf = (rec) => (catalogByHandle[rec.handle] && catalogByHandle[rec.handle].name) || TYPE[rec.type] || `Type ${rec.type}`;
  const unitOf = (rec) => (catalogByHandle[rec.handle] && catalogByHandle[rec.handle].units) || UNITS[rec.type] || "";

  const el = (id) => document.getElementById(id);
  const rowsBody = el("sensor-rows");
  const rows = new Map();

  function fmtBytes(b) {
    if (b < 1024) return `${b | 0} B/s`;
    if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB/s`;
    return `${(b / 1024 / 1024).toFixed(2)} MB/s`;
  }
  const magnitude = (v) => (v.length >= 3 ? Math.hypot(v[0], v[1], v[2]) : NaN);

  function upsert(rec) {
    const key = `${rec.type}:${rec.handle}`;
    let tr = rows.get(key);
    if (!tr) {
      tr = document.createElement("tr");
      tr.innerHTML = `<td class="sname"></td><td class="vals"></td><td class="mag num"></td>
                      <td class="hz num"></td><td class="acc num"></td><td class="lost num"></td>`;
      rowsBody.appendChild(tr);
      rows.set(key, tr);
      el("empty-note").style.display = "none";
    }
    const u = unitOf(rec) ? ` ${unitOf(rec)}` : "";
    tr.querySelector(".sname").textContent = nameOf(rec);
    tr.querySelector(".vals").textContent = rec.v.map((x) => x.toFixed(3)).join(", ") + u;
    const m = magnitude(rec.v);
    tr.querySelector(".mag").textContent = isNaN(m) ? "—" : m.toFixed(3);
    tr.querySelector(".hz").textContent = rec.hz ? rec.hz.toFixed(1) : "—";
    const acc = tr.querySelector(".acc");
    acc.textContent = rec.acc; acc.className = `acc num acc-${rec.acc}`;
    tr.querySelector(".lost").textContent = rec.lost || 0;
  }

  SensorNet.on("open", () => {
    const s = el("status"); s.classList.add("connected"); s.classList.remove("disconnected");
    el("status-text").textContent = "";
  });
  SensorNet.on("close", () => {
    const s = el("status"); s.classList.remove("connected"); s.classList.add("disconnected");
    el("status-text").textContent = "disconnected";
  });

  // Control-channel events (Phase 2)
  SensorNet.on("phone_connected", (msg) => {
    catalogByHandle = {};
    (msg.sensors || []).forEach((s) => { catalogByHandle[s.handle] = { name: s.name, units: s.units }; });
    el("s-device").textContent = `${msg.model || "phone"} (Android ${msg.android || "?"})`;
  });
  SensorNet.on("phone_disconnected", () => {
    catalogByHandle = {};
    el("s-device").textContent = "— disconnected —";
  });

  SensorNet.on("data", (msg) => {
    if (!catalogByHandle || Object.keys(catalogByHandle).length === 0) {
      el("s-device").textContent = "0x" + (msg.device_id >>> 0).toString(16).padStart(8, "0");
    }
    msg.records.forEach(upsert);
    el("s-active").textContent = String(rows.size);
  });
  SensorNet.on("stats", (msg) => {
    el("s-pps").textContent = msg.pps;
    el("s-bps").textContent = fmtBytes(msg.bps);
    el("s-records").textContent = msg.records;
    el("s-derr").textContent = msg.decode_errors;
    if ("recording" in msg) setRec(msg.recording, msg.rec_rows);
  });

  // Record toggle (Phase 6) — sends a command over the same WebSocket.
  const recBtn = el("rec-btn");
  let recording = false;
  function setRec(active, rows) {
    recording = !!active;
    if (!recBtn) return;
    recBtn.classList.toggle("active", recording);
    recBtn.textContent = recording ? "■ Stop" + (rows ? " (" + rows + ")" : "") : "● Record";
  }
  if (recBtn) {
    recBtn.addEventListener("click", () => SensorNet.send({ cmd: recording ? "record_stop" : "record_start" }));
  }
  SensorNet.on("recording", (m) => setRec(m.active, m.rows));
  SensorNet.on("debug", (m) => {
    el("s-latency").textContent = m.latency_ms_p50 + "/" + m.latency_ms_p95 + " ms";
    el("s-loss").textContent = m.loss_pct + "%";
    el("s-jitter").textContent = m.jitter_ms + " ms";
    if ("phone_latency_ms_p50" in m) el("s-phonelat").textContent = m.phone_latency_ms_p50 + "/" + m.phone_latency_ms_p95 + " ms";
    if (m.active_sensors != null) el("s-active").textContent = String(m.active_sensors);
  });
})();
