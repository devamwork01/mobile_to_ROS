import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { history } from "../../telemetry/liveHistory.js";
import { AXIS } from "../../telemetry/signals.js";
import { frameIntervalMs, report } from "../../lib/renderBudget.js";
import { useThemeStamp } from "../../lib/theme.js";

import { seriesSpec, withGaps } from "./seriesSpec.js";

const COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];
// Frozen end time per handle while paused. Module-level so it survives the remount that
// maximise/restore causes: the paused window stays the one the user was inspecting.
const pausedAt = new Map();

// Break threshold: well above this sensor's normal spacing (median of recent intervals).
function gapThreshold(t) {
  const n = Math.min(t.length - 1, 50);
  if (n < 1) return 0.5;
  const d = [];
  for (let i = t.length - n; i < t.length; i++) d.push(t[i] - t[i - 1]);
  d.sort((a, b) => a - b);
  return Math.max(0.5, 5 * d[d.length >> 1]);
}

const token = (css, name) => `rgb(${css.getPropertyValue(name).trim().split(/\s+/).join(",")})`;

// Live plot of one handle from the shared history. Keeps the old GraphPanel safeguards:
// shared render budget, no drawing while off-screen, re-measure + redraw on becoming visible.
// fade(): the raw trace sits behind the filtered one when a filter is active.
const fade = (hex) => `${hex}59`;

export default function SensorGraph({ handle, kind, window: win, paused, show, filtered = false, trace = "both" }) {
  const host = useRef(null);
  const uRef = useRef(null);
  const st = useRef({ dirty: true, force: true, win, paused, show, filtered });
  st.current.win = win;
  st.current.paused = paused;
  st.current.show = show;
  st.current.filtered = filtered;
  const themeStamp = useThemeStamp();

  useEffect(() => {
    const el = host.current;
    if (!el || handle == null) return;
    const spec = seriesSpec(kind);
    const css = getComputedStyle(document.documentElement);
    const axis = {
      stroke: token(css, "--muted"),
      grid: { stroke: token(css, "--line"), width: 1 },
      ticks: { stroke: token(css, "--line") },
      font: "10px ui-monospace, Consolas, monospace",
    };
    const series = [{}];
    spec.labels.forEach((l, i) => series.push({ label: l, stroke: filtered ? fade(COLORS[i]) : COLORS[i], width: filtered ? 1.1 : 1.4, points: { show: false } }));
    if (spec.mag) series.push({ label: "|v|", stroke: token(css, "--fg"), width: 1.2, dash: [4, 3], points: { show: false }, show: false });
    if (filtered) spec.labels.forEach((l, i) => series.push({ label: `f${l}`, stroke: COLORS[i], width: 1.8, points: { show: false } }));
    const size = () => ({ width: Math.max(el.clientWidth, 100), height: Math.max(el.clientHeight, 80) });
    const u = new uPlot(
      {
        ...size(),
        series,
        legend: { show: false },
        cursor: { show: true, points: { size: 5 } },
        padding: [8, 10, 0, 4],
        scales: { x: { time: false, range: () => [-st.current.win, 0] } },
        axes: [{ ...axis, values: (_, vals) => vals.map((x) => `${x.toFixed(0)}s`) }, { ...axis, size: 50 }],
      },
      [[], ...series.slice(1).map(() => [])],
      el
    );
    uRef.current = u;
    st.current.dirty = true;
    st.current.force = true; // draw once even if paused
    const into = history.scratch();
    const mag = new Float64Array(into.t.length);
    let visible = true;
    let raf = 0;
    let last = 0;
    const unsub = history.subscribe(handle, () => {
      st.current.dirty = true;
    });
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting && !visible) {
          u.setSize(size());
          st.current.dirty = true;
          st.current.force = true;
        }
        visible = e.isIntersecting;
      },
      { threshold: 0.01 }
    );
    io.observe(el);
    const ro = new ResizeObserver(() => {
      u.setSize(size());
      st.current.dirty = true;
      st.current.force = true;
    });
    ro.observe(el);
    const tick = (now) => {
      raf = requestAnimationFrame(tick);
      const s = st.current;
      if (!s.dirty || !visible || (s.paused && !s.force)) return;
      if (now - last < frameIntervalMs("plot")) return; // coalesce; stays dirty for the next slot
      last = now;
      s.dirty = false;
      s.force = false;
      let until;
      if (s.paused) {
        if (!pausedAt.has(handle)) {
          const last = history.latest(handle);
          if (last) pausedAt.set(handle, last.t);
        }
        until = pausedAt.get(handle);
      } else pausedAt.delete(handle);
      const { t, v, vf } = history.read(handle, s.win, { until, into });
      const data = [t];
      for (let i = 0; i < spec.n; i++) data.push(v[i] || new Array(t.length).fill(null));
      if (spec.mag) {
        const m = mag.subarray(0, t.length);
        if (s.show?.[spec.n]) for (let j = 0; j < t.length; j++) m[j] = Math.hypot(data[1][j] ?? NaN, data[2][j] ?? NaN, data[3][j] ?? NaN);
        data.push(m); // hidden series: contents unused, no per-frame math
      }
      if (filtered) for (let i = 0; i < spec.n; i++) data.push(vf[i] || new Array(t.length).fill(null));
      u.setData(withGaps(data, gapThreshold(t)));
      report("plot");
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
      unsub();
      io.disconnect();
      ro.disconnect();
      u.destroy();
      uRef.current = null;
    };
  }, [handle, kind, themeStamp, filtered]);

  // Window / pause changes: redraw at the next frame slot.
  useEffect(() => {
    st.current.dirty = true;
    st.current.force = true;
    if (!paused) pausedAt.delete(handle);
  }, [win, paused, handle]);

  // Visibility: per-axis toggles (maximised view) x Raw / Filtered / Both.
  useEffect(() => {
    const u = uRef.current;
    if (!u || !show) return;
    const spec = seriesSpec(kind);
    const n = spec.n;
    const setVis = (idx, on) => {
      const s = u.series[idx];
      if (s && s.show !== on) u.setSeries(idx, { show: on });
    };
    const rawOn = !filtered || trace !== "filtered";
    for (let i = 0; i < n; i++) setVis(1 + i, show[i] && rawOn);
    if (spec.mag) setVis(1 + n, show[n] && rawOn);
    if (filtered) {
      const base = 1 + n + (spec.mag ? 1 : 0);
      for (let i = 0; i < n; i++) setVis(base + i, show[i] && trace !== "raw");
    }
    st.current.dirty = true;
    st.current.force = true; // e.g. |v| just turned on: compute it now
  }, [show, handle, kind, themeStamp, filtered, trace]);

  return <div ref={host} className="absolute inset-0 pt-1" />;
}
