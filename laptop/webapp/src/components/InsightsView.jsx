import { useEffect, useMemo, useState } from "react";
import { Icons } from "../icons.js";
import { listReports, getReport } from "../lib/reportsApi.js";
import { useReportsVersion, useOpenReportRequest, clearOpenReport } from "../telemetry/insights.js";
import { fmtSig, reportToCsv, PRESET_LABEL } from "../lib/insightsFormat.js";
import { downloadText } from "../lib/snapshot.js";
import { signalMeta, AXIS } from "../telemetry/signals.js";
import LogChart from "./shell/LogChart.jsx";

const AX_COLOR = { x: AXIS.X, y: AXIS.Y, z: AXIS.Z, w: "#b57edc", "|v|": "#e6edf5" };
const fmtDur = (s) => (s == null ? "—" : s < 60 ? `${s.toFixed(1)} s` : `${Math.floor(s / 60)} min ${Math.round(s % 60)} s`);
const fmtWhen = (iso) => (iso ? new Date(iso).toLocaleString([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }) : "—");

function SensorCard({ s }) {
  const label = signalMeta(s.type).name || s.name || `Type ${s.type}`;
  const axes = Object.entries(s.axes || {});
  if (s.magnitude) axes.push(["|v|", s.magnitude]);
  const [ax, setAx] = useState(axes[0]?.[0]);
  const sel = axes.find(([k]) => k === ax)?.[1];
  const psdSeries = useMemo(() => (sel?.psd?.f?.length ? [{ label: ax, color: AX_COLOR[ax], x: sel.psd.f, y: sel.psd.psd }] : []), [sel, ax]);
  const adevSeries = useMemo(() => Object.entries(s.adev || {}).map(([k, pts]) => ({ label: k, color: AX_COLOR[k], x: pts.map((p) => p[0]), y: pts.map((p) => p[1]) })), [s]);
  const marks = useMemo(() => Object.entries(s.adev_points || {}).flatMap(([k, p]) => [
    p.random_walk ? { x: 1, y: p.random_walk, color: AX_COLOR[k] } : null,
    p.bias_instability ? { x: p.bi_tau, y: p.bias_instability, color: "#e3a635" } : null,
  ].filter(Boolean)), [s]);
  const r = s.rate || {};
  return (
    <section className="panel p-4 flex flex-col gap-3">
      <div className="flex items-baseline gap-2 flex-wrap">
        <h3 className="text-sm font-semibold">{label}</h3>
        <span className="text-[11px] text-faint">{[s.name, s.unit, `${s.samples.toLocaleString()} samples`].filter(Boolean).join(" · ")}</span>
        <span className="ml-auto text-[11px] num text-muted">{r.rate_hz ? `${r.rate_hz.toFixed(1)} Hz` : "—"} · jitter {r.jitter_ms != null ? `${r.jitter_ms.toFixed(2)} ms` : "—"} · gaps {r.gaps ?? 0} · lost {r.lost ?? 0}</span>
      </div>
      {s.insufficient ? (
        <div className="text-xs text-faint">Not enough data for this sensor.</div>
      ) : (
        <>
          <table className="w-full text-[12px] num">
            <thead className="text-[10px] uppercase tracking-[0.1em] text-faint font-sans">
              <tr><th className="text-left font-medium py-1">Axis</th><th className="text-right font-medium">Mean</th><th className="text-right font-medium">Bias</th><th className="text-right font-medium normal-case">σ</th><th className="text-right font-medium">p-p</th><th className="text-right font-medium">Noise density /√Hz</th><th className="text-right font-medium">Drift /min</th></tr>
            </thead>
            <tbody>
              {axes.map(([k, a]) => (
                <tr key={k} onClick={() => setAx(k)} className={`border-t border-line cursor-pointer ${k === ax ? "bg-surface-2" : "hover:bg-surface-2/50"}`}>
                  <td className="py-1" style={{ color: AX_COLOR[k] }}>{k}</td>
                  <td className="text-right">{fmtSig(a.mean)}</td><td className="text-right">{fmtSig(a.bias)}</td><td className="text-right">{fmtSig(a.std)}</td>
                  <td className="text-right">{fmtSig(a.p2p)}</td><td className="text-right">{fmtSig(a.noise_density)}</td><td className="text-right">{fmtSig(a.drift_per_min)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="grid gap-4 lg:grid-cols-2">
            <div>
              <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Spectrum (PSD) · axis {ax}</div>
              {psdSeries.length ? <LogChart series={psdSeries} xLabel="Hz" height={220} legend={false} /> : <div className="text-xs text-faint">No spectrum for this sensor (on-change or too slow).</div>}
            </div>
            <div>
              <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Allan deviation</div>
              {adevSeries.length ? (
                <>
                  <LogChart series={adevSeries} xLabel="τ (s)" height={220} marks={marks} />
                  <div className="text-[11px] num text-muted mt-1 flex flex-wrap gap-x-4">
                    {Object.entries(s.adev_points || {}).map(([k, p]) => (
                      <span key={k}><span style={{ color: AX_COLOR[k] }}>{k}</span> random walk {fmtSig(p.random_walk)} · bias instability {fmtSig(p.bias_instability)} @ {fmtSig(p.bi_tau)} s</span>
                    ))}
                  </div>
                </>
              ) : (
                <div className="text-xs text-faint">Allan deviation needs a run of 2 min or longer (accelerometer / gyroscope).</div>
              )}
            </div>
          </div>
        </>
      )}
    </section>
  );
}

export default function InsightsView() {
  const version = useReportsVersion();
  const openReq = useOpenReportRequest();
  const [list, setList] = useState(null);
  const [selId, setSelId] = useState(null);
  const [rep, setRep] = useState(null);
  const [err, setErr] = useState(null);

  useEffect(() => { listReports().then(setList).catch((e) => { setErr(e.message); setList([]); }); }, [version]);
  useEffect(() => { if (openReq) { setSelId(openReq); clearOpenReport(); } }, [openReq]);
  useEffect(() => { if (!selId && list?.length) setSelId(list[0].id); }, [list, selId]);
  useEffect(() => { if (selId) { setRep(null); getReport(selId).then(setRep).catch((e) => setErr(e.message)); } }, [selId, version]);

  if (list === null) return <div className="panel p-8 grid place-items-center text-sm text-muted">Loading reports…</div>;
  if (list.length === 0) {
    return (
      <div className="panel p-8 grid place-items-center text-center text-faint">
        <Icons.Sigma size={28} className="mx-auto mb-2 opacity-50" />
        <div className="text-sm font-medium text-muted">No reports yet</div>
        <div className="text-xs">Start a Test run from the Dashboard, or Analyse a recording.</div>
        {err && <div className="text-xs text-err mt-2">{err}</div>}
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 lg:grid-cols-[minmax(0,300px)_minmax(0,1fr)] gap-4 items-start">
      <section className="panel p-3 max-h-[75vh] overflow-y-auto flex flex-col gap-2">
        <h2 className="text-xs font-semibold uppercase tracking-[0.12em] text-muted px-1">Reports ({list.length})</h2>
        {list.map((r) => (
          <button key={r.id} onClick={() => setSelId(r.id)} className={`card p-3 text-left ${selId === r.id ? "border-accent" : "hover:border-line2"}`}>
            <div className="flex items-center gap-2 text-sm font-medium">
              {PRESET_LABEL[r.preset] || r.preset} · {fmtDur(r.duration_s)}
              {r.complete === false && <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-warn/15 text-warn">incomplete</span>}
            </div>
            <div className="text-[11px] text-faint num mt-0.5">{fmtWhen(r.created)} · {r.device?.model || "—"} · {r.sensor_count} sensors</div>
          </button>
        ))}
      </section>
      <div className="flex flex-col gap-4 min-w-0">
        {!rep ? (
          <div className="panel p-8 grid place-items-center text-sm text-muted">Loading report…</div>
        ) : (
          <>
            <section className="panel p-4 flex items-center gap-3 flex-wrap">
              <div>
                <div className="text-sm font-semibold">{PRESET_LABEL[rep.preset] || rep.preset} run · {fmtDur(rep.duration_s)}{rep.requested_s && Math.abs(rep.requested_s - rep.duration_s) > 1 ? ` (of ${fmtDur(rep.requested_s)})` : ""}</div>
                <div className="text-[11px] text-faint">{rep.device?.model || "—"}{rep.device?.android ? ` · Android ${rep.device.android}` : ""} · {fmtWhen(rep.created)} · {rep.id}</div>
              </div>
              {rep.complete === false && <span className="text-[11px] px-2 py-1 rounded-md bg-warn/15 text-warn">Incomplete: the stream dropped during this run</span>}
              <div className="ml-auto flex gap-2">
                <button className="btn-ghost text-xs py-1.5" onClick={() => downloadText(`${rep.id}.report.json`, JSON.stringify(rep, null, 2), "application/json")}><Icons.Download size={14} /> JSON</button>
                <button className="btn-ghost text-xs py-1.5" onClick={() => downloadText(`${rep.id}.report.csv`, reportToCsv(rep))}><Icons.Download size={14} /> CSV</button>
              </div>
            </section>
            {rep.sensors.length === 0 && <div className="panel p-6 text-sm text-faint">No sensor data was recorded in this run.</div>}
            {rep.sensors.map((s) => <SensorCard key={s.handle} s={s} />)}
          </>
        )}
      </div>
    </div>
  );
}
