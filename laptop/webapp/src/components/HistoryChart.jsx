import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { signalMeta, AXIS } from "../telemetry/signals.js";

const rgba = (hex, a) => {
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
};

function seriesStyle(type) {
  const meta = signalMeta(type);
  if (meta.kind === "orientation") return { labels: ["qx", "qy", "qz", "qw"], colors: [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"] };
  if (meta.kind === "vector") return { labels: ["X", "Y", "Z"], colors: [AXIS.X, AXIS.Y, AXIS.Z] };
  return { labels: [meta.name], colors: ["#3d7bfd"] };
}

// q: server LOD result { ncomp, t:[sec from q.start], avg/min/max:[[per comp]], type, start(ns) }
// onZoom(startNs, endNs): drag-select a sub-range -> parent re-queries the server at higher
// resolution for that window (true LOD). onReset: back to the full range.
export default function HistoryChart({ q, onZoom, onReset, zoomed = false }) {
  const host = useRef(null);
  const onZoomRef = useRef(onZoom);
  onZoomRef.current = onZoom;

  useEffect(() => {
    if (!host.current || !q || !q.buckets) return;
    const { labels, colors } = seriesStyle(q.type);
    const ncomp = Math.min(q.ncomp, labels.length);
    const xs = q.t;

    const axisStyle = { stroke: "#8b95a4", grid: { stroke: "#1c222b", width: 1 }, ticks: { stroke: "#1c222b" } };
    const series = [{}];
    const data = [xs];
    const bands = [];
    for (let c = 0; c < ncomp; c++) {
      const col = colors[c] || "#8b95a4";
      series.push({ label: `${labels[c]}·max`, stroke: "transparent", points: { show: false } });
      series.push({ label: `${labels[c]}·min`, stroke: "transparent", points: { show: false } });
      series.push({ label: labels[c], stroke: col, width: 1.5, points: { show: false } });
      data.push(q.max[c], q.min[c], q.avg[c]);
      bands.push({ series: [series.length - 3, series.length - 2], fill: rgba(col, 0.16) });
    }

    const opts = {
      width: host.current.clientWidth || 800,
      height: 300,
      series,
      bands,
      scales: { x: { time: false } },
      axes: [
        Object.assign({ values: (u, v) => v.map((x) => x.toFixed(x < 10 ? 2 : 0) + "s") }, axisStyle),
        Object.assign({}, axisStyle),
      ],
      legend: { show: false },
      // Drag selects a sub-range but does NOT rescale locally — we re-query the server for it.
      cursor: { drag: { x: true, y: false, setScale: false } },
      hooks: {
        setSelect: [
          (u) => {
            const { left, width } = u.select;
            if (width < 4) return; // ignore clicks
            const x0 = u.posToVal(left, "x");
            const x1 = u.posToVal(left + width, "x");
            u.setSelect({ left: 0, width: 0, top: 0, height: 0 }, false);
            if (q.start != null && onZoomRef.current) {
              onZoomRef.current(Math.round(q.start + x0 * 1e9), Math.round(q.start + x1 * 1e9));
            }
          },
        ],
      },
      padding: [12, 16, 4, 8],
    };
    const u = new uPlot(opts, data, host.current);
    const ro = new ResizeObserver(() => u.setSize({ width: host.current.clientWidth || 800, height: 300 }));
    ro.observe(host.current);
    return () => {
      ro.disconnect();
      u.destroy();
    };
  }, [q]);

  if (!q || !q.buckets) {
    return <div className="min-h-[300px] grid place-items-center text-sm text-faint">No samples for this signal in range.</div>;
  }
  const { labels, colors } = seriesStyle(q.type);
  const ncomp = Math.min(q.ncomp, labels.length);
  return (
    <div>
      <div className="flex items-center gap-4 flex-wrap mb-2 text-xs text-muted">
        {Array.from({ length: ncomp }, (_, c) => (
          <span key={c} className="flex items-center gap-1.5">
            <span className="w-2.5 h-2.5 rounded-sm" style={{ background: colors[c] || "#8b95a4" }} />
            {labels[c]}
          </span>
        ))}
        {zoomed && (
          <button onClick={onReset} className="btn-ghost text-xs py-1">
            Reset zoom
          </button>
        )}
        <span className="ml-auto num text-faint">
          {q.raw.toLocaleString()} raw → {q.buckets} buckets · shaded = min/max envelope · drag to zoom
        </span>
      </div>
      <div ref={host} className="w-full" />
    </div>
  );
}
