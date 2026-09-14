import { useEffect, useState } from "react";
import { stats } from "../lib/renderBudget.js";

// Dev-only performance HUD. Off by default; enable with ?perf in the URL.
// Shows the effective render rates (proves "alive during scroll", never 0) and the 3D
// canvas drawing-buffer size (watches the ADR-001 §7 bound). Polls at 2 Hz — it never
// participates in the visualization frame loop.
export default function PerfOverlay() {
  const [s, setS] = useState({ scrolling: false, fps3d: 0, hzPlot: 0 });
  const [buf, setBuf] = useState(null);

  useEffect(() => {
    const id = setInterval(() => {
      setS(stats());
      const cv = document.querySelector("canvas"); // 3D canvas is first in the DOM
      if (cv) setBuf({ w: cv.width, h: cv.height, mp: +((cv.width * cv.height) / 1e6).toFixed(2) });
    }, 500);
    return () => clearInterval(id);
  }, []);

  const Row = ({ label, value, warn }) => (
    <div className="flex items-center justify-between gap-4">
      <span className="text-faint">{label}</span>
      <span className={`num ${warn ? "text-warn" : "text-fg"}`}>{value}</span>
    </div>
  );

  return (
    <div className="fixed bottom-3 right-3 z-[100] card p-3 text-[11px] shadow-panel w-44 select-none">
      <div className="text-[10px] uppercase tracking-wider text-muted mb-1.5">Perf</div>
      <Row label="scroll" value={s.scrolling ? "SCROLLING" : "idle"} warn={s.scrolling} />
      <Row label="3D" value={`${s.fps3d} fps`} warn={s.fps3d === 0} />
      <Row label="plot" value={`${s.hzPlot} Hz`} />
      {buf && <Row label="canvas" value={`${buf.mp} MP`} warn={buf.mp > 0.6} />}
      {buf && <div className="text-faint num text-[10px] text-right">{buf.w}×{buf.h}</div>}
    </div>
  );
}
