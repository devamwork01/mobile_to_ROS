export function seriesSpec(kind) {
  return kind === "orientation"
    ? { labels: ["qx", "qy", "qz", "qw"], n: 4, mag: false }
    : { labels: ["X", "Y", "Z"], n: 3, mag: true };
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
