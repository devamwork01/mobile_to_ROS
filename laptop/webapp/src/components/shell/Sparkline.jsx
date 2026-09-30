// Tiny SVG trend line; values are plotted min..max over the given box.
export default function Sparkline({ values, width = 44, height = 14, className = "" }) {
  const vs = (values || []).filter(Number.isFinite);
  if (vs.length < 2) return <svg width={width} height={height} className={className} aria-hidden />;
  let lo = Math.min(...vs);
  let hi = Math.max(...vs);
  if (hi - lo < 1e-9) {
    hi += 1;
    lo -= 1;
  }
  const pts = vs
    .map((v, i) => `${((i / (vs.length - 1)) * width).toFixed(1)},${(height - 1 - ((v - lo) / (hi - lo)) * (height - 2)).toFixed(1)}`)
    .join(" ");
  return (
    <svg width={width} height={height} className={className} aria-hidden>
      <polyline points={pts} fill="none" stroke="rgb(var(--accent))" strokeWidth="1.2" strokeLinejoin="round" />
    </svg>
  );
}
