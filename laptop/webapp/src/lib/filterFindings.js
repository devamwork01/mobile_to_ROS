// Per-axis spectrum findings (server "filter_report") as display strings for the filter pane.
const AX = ["X", "Y", "Z"];
const DASH = "–";
const num = (x) => +Number(x).toFixed(2);
const sig = (x) => (x == null ? DASH : Number(x).toPrecision(3));

export function findingsRows(report) {
  if (!report || !Array.isArray(report.axes)) return [];
  return report.axes.map((a, i) => ({
    axis: AX[i] || String(i),
    noise: a.noise_density == null ? DASH : a.noise_density.toExponential(1),
    lowpass: a.cutoff_hz == null ? DASH : `${num(a.cutoff_hz)} Hz · ${a.order === 2 ? "2nd" : "4th"}`,
    delay: a.cutoff_hz == null ? DASH : `${Math.round(a.delay_ms)} ms`,
    notches: (a.notches || []).length ? a.notches.map((n) => `${num(n.hz)} Hz ×${Math.round(n.prominence)}`).join(", ") : DASH,
    sigma: `${sig(a.sigma_raw)} → ${sig(a.sigma_filtered)}`,
    gain: a.sigma_filtered > 0 ? `${(a.sigma_raw / a.sigma_filtered).toFixed(1)}×` : DASH,
  }));
}
