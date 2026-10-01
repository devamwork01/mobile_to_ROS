export function seriesSpec(kind) {
  if (kind === "orientation") return { labels: ["qx", "qy", "qz", "qw"], n: 4, mag: false };
  if (kind === "scalar") return { labels: ["value"], n: 1, mag: false };
  return { labels: ["X", "Y", "Z"], n: 3, mag: true };
}

// Smallest y span shown for single-value sensors, by unit. Without it a barometer's 0.01 hPa
// quantization steps (or a light sensor's 1 lx flicker) are stretched to fill the whole plot.
const MIN_SPAN = { hPa: 1, lx: 10, "°C": 1, "%": 2, cm: 1 };
export const minSpanFor = (unit) => MIN_SPAN[unit] ?? 0;

// y range for [min, max] of the visible data: at least `minSpan` wide (centred), else the data
// plus a 5 % margin.
export function yRange(min, max, minSpan) {
  if (!Number.isFinite(min) || !Number.isFinite(max)) return [0, 1];
  const span = max - min;
  if (span < minSpan) {
    const mid = (min + max) / 2;
    return [mid - minSpan / 2, mid + minSpan / 2];
  }
  const pad = span > 0 ? span * 0.05 : 1;
  return [min - pad, max + pad];
}

// Break the line wherever consecutive samples are more than `gapS` apart (a phone outage or a
// paused sensor), so the plot never draws a straight bridge that looks like real data.
// data = [t, ...series]; returns the same arrays when there is no gap.
export function withGaps(data, gapS) {
  const t = data[0];
  const breaks = [];
  for (let i = 1; i < t.length; i++) if (t[i] - t[i - 1] > gapS) breaks.push(i);
  if (breaks.length === 0) return data;
  return data.map((a, k) => {
    const out = [];
    let b = 0;
    for (let i = 0; i < a.length; i++) {
      if (b < breaks.length && breaks[b] === i) {
        out.push(k === 0 ? t[i - 1] : null);
        b++;
      }
      out.push(a[i]);
    }
    return out;
  });
}
