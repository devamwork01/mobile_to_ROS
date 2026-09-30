export function seriesSpec(kind) {
  return kind === "orientation"
    ? { labels: ["qx", "qy", "qz", "qw"], n: 4, mag: false }
    : { labels: ["X", "Y", "Z"], n: 3, mag: true };
}
