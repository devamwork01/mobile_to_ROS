// Per-axis spectrum findings (server "filter_report") as display strings for the filter pane.
const AX = ["X", "Y", "Z"];
const DASH = "–";
const num = (x) => +Number(x).toFixed(2);
const sig = (x) => (x == null ? DASH : Number(x).toPrecision(3));

export function findingsRows(report) {
  if (!report || !Array.isArray(report.axes)) return [];
  return report.axes.map((a, i) => {
    // The measured sensor noise before -> after when the report has it (noise-aware --filter); else
    // the capture's sigma, which includes the motion itself.
    const noisy = a.noise_raw != null && a.noise_filtered != null;
    const [b, f] = noisy ? [a.noise_raw, a.noise_filtered] : [a.sigma_raw, a.sigma_filtered];
    return {
    axis: AX[i] || String(i),
    noise: a.noise_density == null ? DASH : a.noise_density.toExponential(1),
    lowpass: a.cutoff_hz == null ? DASH : `${num(a.cutoff_hz)} Hz · ${a.order === 2 ? "2nd" : "4th"}`,
    delay: a.cutoff_hz == null ? DASH : `${Math.round(a.delay_ms)} ms`,
    notches: (a.notches || []).length ? a.notches.map((n) => `${num(n.hz)} Hz ×${Math.round(n.prominence)}`).join(", ") : DASH,
    sigma: `${sig(b)} → ${sig(f)}`,
    gain: f > 0 ? `${(b / f).toFixed(1)}×` : DASH,
    what: noisy ? "noise" : "σ",
    };
  });
}
