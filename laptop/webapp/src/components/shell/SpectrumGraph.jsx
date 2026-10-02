import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { usePsd } from "../../telemetry/insights.js";
import { sendCommand } from "../../telemetry/store.js";
import { AXIS } from "../../telemetry/signals.js";
import { useThemeStamp } from "../../lib/theme.js";
import { logTick, expTick } from "../../lib/insightsFormat.js";
import { responseCurve } from "../../lib/filterResponse.js";
import { clampDecades } from "../../lib/logPlot.js";

const COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];
const token = (css, n) => `rgb(${css.getPropertyValue(n).trim().split(/\s+/).join(",")})`;

const fade = (hex) => `${hex}66`;

// Server-computed PSD (full rate). With a filter: raw curves fade, the filtered ("after") curves are
// drawn on top, plus the filter's response scaled onto the plot (dashed; peak = top of the raw
// spectrum, so its shape reads as attenuation). A pending suggestion is marked with vertical lines.
export default function SpectrumGraph({ handle, labels, filter = null, fs = null, suggestion = null }) {
  const host = useRef(null);
  const uRef = useRef(null);
  const marks = useRef([]);
  const data = usePsd(handle);
  const theme = useThemeStamp();
  const withFilter = !!filter;
  const perAxis = !!filter?.axes; // per-axis filter: one response curve per axis

  useEffect(() => {
    const sub = () => sendCommand({ cmd: "psd_subscribe", handles: [handle] });
    sub();
    const id = setInterval(sub, 5000);
    return () => clearInterval(id);
  }, [handle]);

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const css = getComputedStyle(document.documentElement);
    const axis = { stroke: token(css, "--muted"), grid: { stroke: token(css, "--line"), width: 1 }, ticks: { stroke: token(css, "--line") }, font: "10px ui-monospace, Consolas, monospace" };
    const series = [{}, ...labels.map((l, i) => ({ label: l, stroke: withFilter ? fade(COLORS[i]) : COLORS[i], width: withFilter ? 1 : 1.3, points: { show: false } }))];
    if (withFilter) {
      labels.forEach((l, i) => series.push({ label: `f${l}`, stroke: COLORS[i], width: 1.6, points: { show: false } }));
      if (perAxis) labels.forEach((l, i) => series.push({ label: `r${l}`, stroke: COLORS[i], width: 1.2, dash: [5, 4], alpha: 0.8, points: { show: false } }));
      else series.push({ label: "response", stroke: token(css, "--fg"), width: 1.2, dash: [5, 4], alpha: 0.6, points: { show: false } });
    }
    const size = () => ({ width: Math.max(el.clientWidth, 100), height: Math.max(el.clientHeight, 80) });
    const accent = token(css, "--accent");
    const u = new uPlot({ ...size(), series, legend: { show: false }, padding: [8, 10, 0, 4],
      scales: { x: { time: false, distr: 3 }, y: { distr: 3 } },
      axes: [{ ...axis, values: (_, v) => v.map((x) => logTick(x, "Hz")) }, { ...axis, size: 56, values: (_, v) => v.map(expTick) }],
      hooks: { draw: [(u) => {
        const ctx = u.ctx;
        for (const mk of marks.current) {
          const x = u.valToPos(mk.hz, "x", true);
          if (!Number.isFinite(x)) continue;
          ctx.save();
          ctx.strokeStyle = accent;
          ctx.setLineDash(mk.kind === "cutoff" ? [6, 4] : [2, 3]);
          ctx.lineWidth = 1.5 * devicePixelRatio;
          ctx.beginPath();
          ctx.moveTo(x, u.bbox.top);
          ctx.lineTo(x, u.bbox.top + u.bbox.height);
          ctx.stroke();
          ctx.restore();
        }
      }] } },
      [[], ...series.slice(1).map(() => [])], el);
    uRef.current = u;
    const ro = new ResizeObserver(() => u.setSize(size()));
    ro.observe(el);
    return () => { ro.disconnect(); u.destroy(); uRef.current = null; };
  }, [handle, labels.length, theme, withFilter, perAxis]);

  // Suggestion markers: cutoff (long dashes) and notches (short dashes).
  useEffect(() => {
    marks.current = suggestion
      ? [...(suggestion.lowpass ? [{ kind: "cutoff", hz: suggestion.lowpass.hz }] : []), ...(suggestion.notches || []).map((n) => ({ kind: "notch", hz: n.hz }))]
      : [];
    uRef.current?.redraw();
  }, [suggestion]);

  useEffect(() => {
    const u = uRef.current;
    if (!u || !data || !data.f.length) return;
    const pos = (arr) => (arr || []).map((y) => (y > 0 ? y : null));
    const raw = labels.map((_, i) => pos(data.psd[i]));
    const cols = [data.f, ...raw];
    if (withFilter) {
      labels.forEach((_, i) => cols.push(pos(data.psd_f && data.psd_f[i])));
      const top = Math.max(...raw.flat().filter((y) => y != null), 1e-30);
      const rate = fs || 2 * data.f[data.f.length - 1];
      if (perAxis) labels.forEach((_, i) => cols.push(responseCurve(filter.axes[i] || filter, rate, data.f, top)));
      else cols.push(responseCurve(filter, rate, data.f, top));
    }
    const { series } = clampDecades(cols.slice(1), 8);
    u.setData([cols[0], ...series]);
  }, [data, labels.length, withFilter, perAxis, filter, fs]);

  return (
    <div className="absolute inset-0 pt-1">
      <div ref={host} className="absolute inset-0" />
      {!data && <div className="absolute inset-0 grid place-items-center text-xs text-faint">Computing spectrum on the server…</div>}
    </div>
  );
}
