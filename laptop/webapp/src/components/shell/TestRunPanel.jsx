import { useEffect, useState } from "react";
import { Icons } from "../../icons.js";
import { useTestRun, dismissTestRun, requestOpenReport, getStats } from "../../telemetry/insights.js";
import { sendCommand } from "../../telemetry/store.js";
import { fmtSig, PRESET_LABEL } from "../../lib/insightsFormat.js";

const mmss = (s) => `${String(Math.floor(s / 60)).padStart(2, "0")}:${String(Math.floor(s % 60)).padStart(2, "0")}`;

export default function TestRunPanel({ rows }) {
  const run = useTestRun();
  const [, tick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, []);
  if (!run) return null;
  const name = (h) => rows.find((r) => r.handle === h)?.label || `#${h}`;
  const pct = run.seconds ? Math.min(100, (100 * (run.elapsed || 0)) / run.seconds) : 0;
  const busy = run.phase === "running" || run.phase === "settling" || run.phase === "analysing";
  return (
    <section className="panel xl:col-span-2 p-3 flex flex-col gap-2 border-accent/40">
      <div className="flex items-center gap-2 text-xs">
        <Icons.FlaskConical size={15} className="text-accent" />
        <span className="font-semibold">Test run · {PRESET_LABEL[run.preset] || run.preset}</span>
        {run.phase === "running" && <span className="num text-muted">{mmss(run.elapsed || 0)} / {mmss(run.seconds || 0)}</span>}
        {run.phase === "settling" && <span className="text-muted">collecting late samples…</span>}
        {run.phase === "analysing" && <span className="text-muted">analysing…</span>}
        {run.phase === "done" && <span className={run.complete ? "text-ok" : "text-warn"}>{run.complete ? "done" : "done (incomplete - the stream dropped)"}</span>}
        {(run.phase === "error" || run.phase === "refused") && <span className="text-err">{run.message}</span>}
        {!run.complete && run.phase === "running" && <span className="text-warn">stream dropped - run will be marked incomplete</span>}
        <div className="ml-auto flex gap-2">
          {run.phase === "running" && <button className="btn-ghost text-xs py-1" onClick={() => sendCommand({ cmd: "testrun_stop" })}><Icons.Square size={12} /> Stop early</button>}
          {run.phase === "done" && <button className="btn-accent text-xs py-1" onClick={() => { requestOpenReport(run.id); dismissTestRun(); }}><Icons.FileText size={12} /> Open report</button>}
          {!busy && <button className="btn-ghost text-xs py-1" onClick={dismissTestRun}>Dismiss</button>}
        </div>
      </div>
      {run.phase === "running" && (
        <>
          <div className="h-1.5 rounded-full bg-surface-3 overflow-hidden"><div className="h-full bg-accent transition-all" style={{ width: `${pct}%` }} /></div>
          <div className="flex flex-wrap gap-x-5 gap-y-1 text-[11px] num">
            {run.preset === "still" && <span className="text-muted font-sans">Keep the phone and robot perfectly still.</span>}
            {(run.handles || []).map((h) => {
              const s = getStats(h);
              return <span key={h} className="text-muted">{name(h)}: σ {s ? s.axes.slice(0, 3).map((a) => fmtSig(a.std)).join(" / ") : "—"}</span>;
            })}
          </div>
        </>
      )}
    </section>
  );
}
