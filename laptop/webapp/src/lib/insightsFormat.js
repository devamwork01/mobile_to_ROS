export const PRESET_LABEL = { still: "Still", capture: "Capture" };
export const DURATIONS = [[30, "30 s"], [120, "2 min"], [300, "5 min"], [600, "10 min"], [1800, "30 min"]];

// 3 significant digits; exponent form for |x| < 1e-3 or >= 1e5.
export function fmtSig(x, digits = 3) {
  if (x == null || !Number.isFinite(x)) return "—";
  if (x === 0) return "0";
  const a = Math.abs(x);
  if (a < 1e-3 || a >= 1e5) {
    const [m, e] = x.toExponential(digits - 1).split("e");
    return `${m}e${Number(e)}`;
  }
  return String(Number(x.toPrecision(digits)));
}

const COLS = ["mean", "bias", "std", "p2p", "noise_density", "drift_per_min"];
const csvCell = (v) => (v == null ? "" : typeof v === "string" && /[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : String(v));

export function reportToCsv(report) {
  const head = "sensor,handle,axis,unit," + COLS.join(",") + ",rate_hz,jitter_ms,gaps,lost,random_walk,bias_instability,bi_tau_s";
  const rows = [head];
  for (const s of report.sensors || []) {
    const name = s.name || `Type ${s.type}`;
    const r = s.rate || {};
    const axes = Object.entries(s.axes || {});
    if (s.magnitude) axes.push(["|v|", s.magnitude]);
    for (const [ax, st] of axes) {
      const ap = (s.adev_points || {})[ax] || {};
      rows.push([name, s.handle, ax, s.unit || "", ...COLS.map((c) => st[c]), r.rate_hz, r.jitter_ms, r.gaps, r.lost,
        ap.random_walk, ap.bias_instability, ap.bi_tau].map(csvCell).join(","));
    }
  }
  return rows.join("\n") + "\n";
}

// Axis tick labels for log-scale uPlot charts. uPlot passes null for log ticks it leaves
// unlabeled, so both return "" for them.
export const logTick = (x, unit = "") => (x == null ? "" : `${+x.toPrecision(2)}${unit}`);
export const expTick = (x) => (x == null ? "" : x.toExponential(0));
