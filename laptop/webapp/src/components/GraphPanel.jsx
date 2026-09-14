import { useEffect, useRef, useState } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { subscribeType } from "../telemetry/store.js";
import { signalMeta, AXIS } from "../telemetry/signals.js";

const WINDOWS = [1, 5, 10, 30, 60];
const AXIS_COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];
const CAP = 6000;

export default function GraphPanel({ sensors }) {
  // sensors: [{type, name}] of active vector-style signals
  const [type, setType] = useState(null);
  const [win, setWin] = useState(10);
  const [paused, setPaused] = useState(false);
  const host = useRef(null);
  const winRef = useRef(win);
  const pausedRef = useRef(paused);
  winRef.current = win;
  pausedRef.current = paused;

  // pick a default/first available type
  useEffect(() => {
    if (sensors.length && (type == null || !sensors.some((s) => s.type === type))) {
      setType(sensors[0].type);
    }
  }, [sensors, type]);

  useEffect(() => {
    if (type == null || !host.current) return;
    const meta = signalMeta(type);
    const nAxis = meta.kind === "orientation" ? 4 : 3;
    const labels = meta.kind === "orientation" ? ["qx", "qy", "qz", "qw"] : ["X", "Y", "Z"];
    const axisStyle = { stroke: "#8b95a4", grid: { stroke: "#1c222b", width: 1 }, ticks: { stroke: "#1c222b" } };
    const series = [{}];
    for (let i = 0; i < nAxis; i++) series.push({ label: labels[i], stroke: AXIS_COLORS[i], width: 1.5, points: { show: false } });

    const xs = [];
    const ys = Array.from({ length: nAxis }, () => []);
    let t0 = null;
    let dirty = false;

    const opts = {
      width: host.current.clientWidth || 600,
      height: 260,
      series,
      scales: {
        x: { time: false, range: (u, min, max) => (max == null || !isFinite(max) ? [0, win] : [max - winRef.current, max]) },
      },
      axes: [Object.assign({ values: (u, v) => v.map((x) => x.toFixed(0) + "s") }, axisStyle), Object.assign({}, axisStyle)],
      legend: { show: true },
      cursor: { show: true, points: { size: 6 } },
      padding: [12, 16, 4, 8],
    };
    const u = new uPlot(opts, [xs, ...ys], host.current);

    const unsub = subscribeType(type, (r) => {
      if (pausedRef.current || !r.v) return;
      if (t0 == null) t0 = r.t;
      xs.push((r.t - t0) / 1e9);
      for (let i = 0; i < nAxis; i++) ys[i].push(r.v[i] != null ? r.v[i] : null);
      dirty = true;
    });

    let raf = 0;
    // Skip redraw only while the chart is scrolled off-screen (data keeps buffering and
    // flushes when it returns). Redraw continues while visible, including during scroll.
    let visible = true;
    const vio = new IntersectionObserver(([e]) => { visible = e.isIntersecting; }, { threshold: 0.01 });
    vio.observe(host.current);
    const tick = () => {
      raf = requestAnimationFrame(tick);
      if (!dirty || !visible) return;
      const xmax = xs[xs.length - 1];
      const cutoff = xmax - winRef.current - 1;
      let drop = 0;
      while (drop < xs.length && xs[drop] < cutoff) drop++;
      if (xs.length - drop > CAP) drop = xs.length - CAP;
      if (drop > 0) {
        xs.splice(0, drop);
        ys.forEach((a) => a.splice(0, drop));
      }
      u.setData([xs, ...ys]);
      dirty = false;
    };
    raf = requestAnimationFrame(tick);

    const ro = new ResizeObserver(() => u.setSize({ width: host.current.clientWidth || 600, height: 260 }));
    ro.observe(host.current);

    return () => {
      cancelAnimationFrame(raf);
      unsub();
      ro.disconnect();
      vio.disconnect();
      u.destroy();
    };
  }, [type]);

  if (sensors.length === 0) {
    return <div className="min-h-[220px] grid place-items-center text-sm text-faint">No streaming signals to plot.</div>;
  }

  return (
    <div>
      <div className="flex items-center gap-2 flex-wrap mb-3">
        <div className="flex items-center gap-1 flex-wrap">
          {sensors.map((s) => (
            <button
              key={s.type}
              onClick={() => setType(s.type)}
              className={`px-3 py-1.5 rounded-lg text-xs transition-colors ${
                type === s.type ? "bg-accent-soft text-accent" : "text-muted hover:text-fg bg-surface-2"
              }`}
            >
              {s.name}
            </button>
          ))}
        </div>
        <div className="ml-auto flex items-center gap-2">
          <select
            value={win}
            onChange={(e) => setWin(Number(e.target.value))}
            className="bg-surface-2 border border-line rounded-lg text-xs px-2 py-1.5 text-fg"
          >
            {WINDOWS.map((w) => (
              <option key={w} value={w}>
                {w}s
              </option>
            ))}
          </select>
          <button onClick={() => setPaused((p) => !p)} className="btn-ghost text-xs">
            {paused ? "Resume" : "Pause"}
          </button>
        </div>
      </div>
      <div ref={host} className="w-full" />
    </div>
  );
}
