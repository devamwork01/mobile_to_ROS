import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { usePsd } from "../../telemetry/insights.js";
import { sendCommand } from "../../telemetry/store.js";
import { AXIS } from "../../telemetry/signals.js";
import { useThemeStamp } from "../../lib/theme.js";
import { logTick, expTick } from "../../lib/insightsFormat.js";

const COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];
const token = (css, n) => `rgb(${css.getPropertyValue(n).trim().split(/\s+/).join(",")})`;

export default function SpectrumGraph({ handle, labels }) {
  const host = useRef(null);
  const uRef = useRef(null);
  const data = usePsd(handle);
  const theme = useThemeStamp();

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
    const series = [{}, ...labels.map((l, i) => ({ label: l, stroke: COLORS[i], width: 1.3, points: { show: false } }))];
    const size = () => ({ width: Math.max(el.clientWidth, 100), height: Math.max(el.clientHeight, 80) });
    const u = new uPlot({ ...size(), series, legend: { show: false }, padding: [8, 10, 0, 4],
      scales: { x: { time: false, distr: 3 }, y: { distr: 3 } },
      axes: [{ ...axis, values: (_, v) => v.map((x) => logTick(x, "Hz")) }, { ...axis, size: 56, values: (_, v) => v.map(expTick) }] },
      [[], ...labels.map(() => [])], el);
    uRef.current = u;
    const ro = new ResizeObserver(() => u.setSize(size()));
    ro.observe(el);
    return () => { ro.disconnect(); u.destroy(); uRef.current = null; };
  }, [handle, labels.length, theme]);

  useEffect(() => {
    const u = uRef.current;
    if (!u || !data || !data.f.length) return;
    const ys = labels.map((_, i) => (data.psd[i] || []).map((y) => (y > 0 ? y : null)));
    u.setData([data.f, ...ys]);
  }, [data, labels.length]);

  return (
    <div className="absolute inset-0 pt-1">
      <div ref={host} className="absolute inset-0" />
      {!data && <div className="absolute inset-0 grid place-items-center text-xs text-faint">Computing spectrum on the server…</div>}
    </div>
  );
}
