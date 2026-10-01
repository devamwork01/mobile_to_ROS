// uPlot's log axis gives up (no ticks, blank chart) when data spans too many decades - a good
// filter's stopband can sit 15 decades below the peak. Keep every curve within `decades` of the
// top: lower values are drawn at the floor; nulls (gaps) stay null.
export function clampDecades(seriesList, decades = 8) {
  let max = null;
  for (const s of seriesList) for (const y of s) if (y != null && y > 0 && (max == null || y > max)) max = y;
  if (max == null) return { series: seriesList, min: null, max: null };
  const min = max / Math.pow(10, decades);
  const series = seriesList.map((s) => s.map((y) => (y == null ? null : y > min ? y : min)));
  return { series, min, max };
}
