import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { useThemeStamp } from "../../lib/theme.js";
import { logTick, expTick } from "../../lib/insightsFormat.js";

const token = (css, n) => `rgb(${css.getPropertyValue(n).trim().split(/\s+/).join(",")})`;

// series: [{label, color, x:[...], y:[...]}] sharing one log-log plot (x arrays may differ; merged).
export default function LogChart({ series, xLabel, height = 240, marks = [], legend = true }) {
  const host = useRef(null);
  const theme = useThemeStamp();
  useEffect(() => {
    const el = host.current;
    if (!el || !series.length) return;
    const xs = [...new Set(series.flatMap((s) => s.x))].sort((a, b) => a - b);
    const ys = series.map((s) => { const m = new Map(s.x.map((x, i) => [x, s.y[i]])); return xs.map((x) => (m.has(x) && m.get(x) > 0 ? m.get(x) : null)); });
    const css = getComputedStyle(document.documentElement);
    const axis = { stroke: token(css, "--muted"), grid: { stroke: token(css, "--line"), width: 1 }, ticks: { stroke: token(css, "--line") }, font: "10px ui-monospace, Consolas, monospace" };
    const u = new uPlot({
      width: Math.max(el.clientWidth, 200), height,
      series: [{}, ...series.map((s) => ({ label: s.label, stroke: s.color, width: 1.4, spanGaps: true, points: { show: false } }))],
      legend: { show: legend },
      scales: { x: { time: false, distr: 3 }, y: { distr: 3 } },
      axes: [{ ...axis, label: xLabel, values: (_, v) => v.map((x) => logTick(x)) }, { ...axis, size: 60, values: (_, v) => v.map(expTick) }],
      hooks: { draw: [(u) => {
        const ctx = u.ctx;
        for (const mk of marks) {
          const cx = u.valToPos(mk.x, "x", true), cy = u.valToPos(mk.y, "y", true);
          if (!Number.isFinite(cx) || !Number.isFinite(cy)) continue;
          ctx.save(); ctx.fillStyle = mk.color; ctx.beginPath(); ctx.arc(cx, cy, 4 * devicePixelRatio, 0, 2 * Math.PI); ctx.fill(); ctx.restore();
        }
      }] },
    }, [xs, ...ys], el);
    const ro = new ResizeObserver(() => u.setSize({ width: Math.max(el.clientWidth, 200), height }));
    ro.observe(el);
    return () => { ro.disconnect(); u.destroy(); };
  }, [series, height, theme, xLabel, marks, legend]);
  return <div ref={host} className="w-full" />;
}
