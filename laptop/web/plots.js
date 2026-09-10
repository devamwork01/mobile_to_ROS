// Real-time rolling plots (uPlot). One chart per streaming sensor, each a
// bounded rolling buffer trimmed to the selected time window (1/5/10/30/60 s)
// so memory never grows without bound. X is elapsed seconds from the first
// sample (phone monotonic clock); Y auto-ranges to the visible window.
(function () {
  if (typeof uPlot === "undefined") {
    const el = document.getElementById("plots");
    if (el) el.textContent = "uPlot failed to load (vendor/uPlot.iife.min.js)";
    return;
  }

  const container = document.getElementById("plots");
  const toolbar = document.getElementById("plots-toolbar");
  const WINDOWS = [1, 5, 10, 30, 60];
  const CAP = 6000; // hard safety cap on points per series
  const COLORS = ["#f04747", "#43b581", "#4a90e2", "#b57edc", "#e0c93a", "#e0863a"];

  let windowSec = 10;
  let t0 = null;
  let names = {};
  const charts = new Map(); // "type:handle" -> {u, xs, ys, n, dirty}

  function renderToolbar() {
    if (!toolbar) return;
    toolbar.innerHTML = "";
    WINDOWS.forEach((w) => {
      const b = document.createElement("button");
      b.textContent = w + "s";
      b.className = "win-btn" + (w === windowSec ? " active" : "");
      b.onclick = () => { windowSec = w; renderToolbar(); };
      toolbar.appendChild(b);
    });
  }
  renderToolbar();

  function labelsFor(type, n) {
    if (n === 1) return ["value"];
    if ((type === 11 || type === 15 || type === 20) && n >= 4) return ["qx", "qy", "qz", "qw", "acc"].slice(0, n);
    if (n >= 3) return ["x", "y", "z", "w", "v4", "v5"].slice(0, n);
    return Array.from({ length: n }, (_, i) => "v" + i);
  }
  const width = () => Math.max(240, container.clientWidth || 600);

  function create(key, type, name, n) {
    const div = document.createElement("div");
    div.className = "chart";
    container.appendChild(div);
    try {
      const labels = labelsFor(type, n);
      const series = [{}];
      for (let i = 0; i < n; i++) {
        series.push({ label: labels[i], stroke: COLORS[i % COLORS.length], width: 1.25, points: { show: false } });
      }
      const axisStyle = { stroke: "#8b949e", grid: { stroke: "#20272f", width: 1 }, ticks: { stroke: "#20272f" } };
      const opts = {
        title: name,
        width: width(),
        height: 150,
        series: series,
        scales: {
          x: {
            time: false,
            range: (u, min, max) => (max == null || !isFinite(max) ? [0, windowSec] : [max - windowSec, max]),
          },
        },
        axes: [Object.assign({ values: (u, vs) => vs.map((v) => v.toFixed(0) + "s") }, axisStyle), Object.assign({}, axisStyle)],
        legend: { show: true },
        cursor: { show: false },
      };
      const xs = [];
      const ys = Array.from({ length: n }, () => []);
      const u = new uPlot(opts, [xs, ...ys], div);
      const c = { u, xs, ys, n, dirty: false, ok: true };
      charts.set(key, c);
      return c;
    } catch (e) {
      div.textContent = "plot error: " + ((e && e.message) || e);
      div.style.color = "#f85149";
      const c = { u: null, xs: [], ys: [], n: n, dirty: false, ok: false };
      charts.set(key, c);
      return c;
    }
  }

  if (window.SensorNet) {
    SensorNet.on("phone_connected", (m) => {
      names = {};
      (m.sensors || []).forEach((s) => { names[s.handle] = s.name; });
    });
    SensorNet.on("data", (msg) => {
      msg.records.forEach((r) => {
        if (!r.v || r.v.length === 0) return;
        if (t0 === null) t0 = r.t;
        const key = r.type + ":" + r.handle;
        let c = charts.get(key);
        if (!c) c = create(key, r.type, names[r.handle] || "Type " + r.type, Math.min(r.v.length, 6));
        if (!c.ok) return;
        c.xs.push((r.t - t0) / 1e9);
        for (let i = 0; i < c.n; i++) c.ys[i].push(r.v[i] !== undefined ? r.v[i] : null);
        c.dirty = true;
      });
    });
  }

  function tick() {
    charts.forEach((c) => {
      if (!c.ok || !c.dirty) return;
      const xmax = c.xs[c.xs.length - 1];
      const cutoff = xmax - windowSec - 1;
      let drop = 0;
      while (drop < c.xs.length && c.xs[drop] < cutoff) drop++;
      if (c.xs.length - drop > CAP) drop = c.xs.length - CAP; // safety cap
      if (drop > 0) {
        c.xs.splice(0, drop);
        c.ys.forEach((a) => a.splice(0, drop));
      }
      c.u.setData([c.xs, ...c.ys]); // resetScales=true: x uses range fn, y auto-fits
      c.dirty = false;
    });
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);

  function resize() {
    const w = width();
    charts.forEach((c) => c.u.setSize({ width: w, height: 150 }));
  }
  if (window.ResizeObserver) new ResizeObserver(resize).observe(container);
  else window.addEventListener("resize", resize);
})();
