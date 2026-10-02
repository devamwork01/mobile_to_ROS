import { useEffect, useRef, useState } from "react";
import { Icons } from "../../icons.js";
import { sendCommand } from "../../telemetry/store.js";
import { AXIS } from "../../telemetry/signals.js";
import {
  useFilterConfig, useFilterSuggestion, useFilterSuggestionAxes, useFilterError, useFilterReport, useInsightStats,
  clearFilterSuggestion, clearFilterError,
} from "../../telemetry/insights.js";
import { validateConfig, configToDraft, draftToConfig, setPerAxis, applySuggestion, followServer, AXIS_NAMES, MAX_NOTCHES } from "../../lib/filterConfig.js";
import { useFilterPane, closeFilterPane, setFilterPaneTab } from "../../lib/filterPane.js";
import { findingsRows } from "../../lib/filterFindings.js";

// Low-pass + notches for one filter: the whole sensor, or one axis when filtering per axis.
function AxisFields({ value, onChange, nyq }) {
  const set = (patch) => onChange({ ...value, ...patch });
  const setNotch = (i, patch) => set({ notches: value.notches.map((n, j) => (j === i ? { ...n, ...patch } : n)) });
  const logPos = (hz) => Math.log10(Math.max(0.1, hz) / 0.1) / Math.log10(nyq / 0.1);
  const fromPos = (p) => +(0.1 * Math.pow(nyq / 0.1, p)).toPrecision(3);
  return (
    <>
      <label className="flex items-center gap-2 text-xs">
        <input type="checkbox" checked={value.lpOn} onChange={(e) => set({ lpOn: e.target.checked })} />
        Low-pass (Butterworth)
        <select value={value.order} disabled={!value.lpOn} onChange={(e) => set({ order: Number(e.target.value) })}
          className="ml-auto bg-surface-2 border border-line rounded-md text-xs px-1 py-0.5">
          <option value={2}>order 2</option><option value={4}>order 4</option>
        </select>
      </label>
      {value.lpOn && (
        <div className="flex items-center gap-2">
          <input type="range" min={0} max={1} step={0.001} value={logPos(value.lpHz)} onChange={(e) => set({ lpHz: fromPos(Number(e.target.value)) })} className="flex-1 accent-[rgb(var(--accent))]" />
          <input type="number" min={0.1} step={0.1} value={value.lpHz} onChange={(e) => set({ lpHz: Number(e.target.value) })}
            className="w-20 bg-surface-2 border border-line rounded-md text-xs px-2 py-1 num" /> <span className="text-xs text-faint">Hz</span>
        </div>
      )}
      <div className="flex flex-col gap-1.5">
        <div className="flex items-center text-xs">
          Notches
          <button disabled={value.notches.length >= MAX_NOTCHES} className="ml-auto text-accent text-xs disabled:opacity-40"
            onClick={() => set({ notches: [...value.notches, { hz: 8, q: 10 }] })}>+ add</button>
        </div>
        {value.notches.map((n, i) => (
          <div key={i} className="flex items-center gap-2 text-xs">
            <input type="number" step={0.1} value={n.hz} onChange={(e) => setNotch(i, { hz: Number(e.target.value) })} className="w-20 bg-surface-2 border border-line rounded-md px-2 py-1 num" /> Hz
            <span className="text-faint">Q</span>
            <input type="number" step={1} value={n.q} onChange={(e) => setNotch(i, { q: Number(e.target.value) })} className="w-16 bg-surface-2 border border-line rounded-md px-2 py-1 num" />
            <button className="ml-auto text-faint hover:text-fg" title="Remove" onClick={() => set({ notches: value.notches.filter((_, j) => j !== i) })}><Icons.X size={13} /></button>
          </div>
        ))}
      </div>
    </>
  );
}

// What the spectrum showed per axis (from --filter or Suggest): noise floor, low-pass and its delay,
// notches with how far each peak stood out, and the expected sigma before -> after.
function Findings({ report }) {
  const rows = findingsRows(report);
  if (!rows.length) return null;
  return (
    <div className="flex flex-col gap-1.5 border-t border-line pt-3">
      <div className="flex items-center text-xs font-semibold">
        Spectrum findings
        <span className="ml-auto text-[10px] font-normal text-faint num">{report.fs} Hz{report.at ? ` · ${report.at.slice(11, 19)}` : ""}</span>
      </div>
      <table className="w-full text-[11px] num">
        <thead className="text-faint">
          <tr>
            <th className="text-left font-normal">Axis</th>
            <th className="text-left font-normal">Noise/√Hz</th>
            <th className="text-left font-normal">Low-pass</th>
            <th className="text-left font-normal">Delay</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.axis} className="border-t border-line/60 align-top">
              <td className="py-1 font-semibold" style={{ color: AXIS[r.axis] }}>{r.axis}</td>
              <td className="py-1">{r.noise}</td>
              <td className="py-1">{r.lowpass}</td>
              <td className="py-1">{r.delay}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {rows.map((r) => (
        <div key={r.axis} className="text-[11px] text-muted num">
          <span className="font-semibold" style={{ color: AXIS[r.axis] }}>{r.axis}</span> notches {r.notches} · σ {r.sigma} ({r.gain})
        </div>
      ))}
    </div>
  );
}

// Edit one sensor's filter. "Suggest" asks the server to propose a config from the live spectrum;
// Apply / Clear go to the server, which owns the truth and broadcasts it to every dashboard.
// "Per axis" (3-axis sensors) gives X / Y / Z their own filter; the open axis tab is shared through
// the pane store so the sensor's spectrum can mark that axis's suggestion.
function PaneBody({ row }) {
  const fs = useInsightStats(row.handle)?.rate_hz || null;
  const report = useFilterReport(row.key);
  const active = useFilterConfig(row.key);
  const suggestion = useFilterSuggestion(row.key);
  const suggestionAxes = useFilterSuggestionAxes(row.key);
  const error = useFilterError(row.key);
  const [draft, setDraft] = useState(() => configToDraft(active));
  const [tab, setTab] = useState(0);
  const [dirty, setDirty] = useState(false); // edited here since it last matched the server
  const [stale, setStale] = useState(false); // the server's filter changed while edited here
  const canPerAxis = row.kind === "vector";
  useEffect(() => {
    if (!suggestion) return;
    setDraft((d) => applySuggestion(d, suggestion, suggestionAxes));
    setDirty(true);
  }, [suggestion, suggestionAxes]);
  const seen = useRef(active);
  useEffect(() => {
    if (seen.current === active) return; // first render: the draft was seeded from it
    seen.current = active;
    const r = followServer(draft, active, dirty);
    setDraft(r.draft);
    setStale(r.stale);
  }, [active]); // eslint-disable-line react-hooks/exhaustive-deps -- react to server changes only
  const reload = () => { setDraft(configToDraft(active)); setDirty(false); setStale(false); };
  useEffect(() => { setFilterPaneTab(draft.perAxis ? tab : null); }, [draft.perAxis, tab]);
  useEffect(() => () => setFilterPaneTab(null), []);
  // Leaving this sensor drops a pending suggestion; errors stay so the panel can show why a filter isn't running.
  useEffect(() => () => clearFilterSuggestion(row.key), [row.key]);
  useEffect(() => {
    const k = (e) => e.key === "Escape" && closeFilterPane();
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, []);
  const cfg = draftToConfig(draft);
  const check = validateConfig(cfg, fs);
  const nyq = fs ? 0.45 * fs : 50;
  const editing = draft.perAxis ? draft.axes[tab] : draft;
  const onEdit = (v) => { setDirty(true); setDraft((d) => (d.perAxis ? { ...d, axes: d.axes.map((a, i) => (i === tab ? v : a)) } : { ...d, ...v })); };

  return (
    <aside className="w-[360px] shrink-0 border-l border-line bg-surface overflow-y-auto p-4 flex flex-col gap-3 max-[900px]:fixed max-[900px]:inset-0 max-[900px]:z-40 max-[900px]:w-auto">
      <div className="flex items-center gap-2">
        <Icons.Filter size={15} className="text-accent shrink-0" />
        <h2 className="text-sm font-semibold truncate">Filter · {row.label}</h2>
        <span className="ml-auto text-[11px] text-faint num whitespace-nowrap">{fs ? `${fs.toFixed(1)} Hz` : "rate unknown"}</span>
        <button title="Close (Esc)" className="text-faint hover:text-fg" onClick={closeFilterPane}><Icons.X size={15} /></button>
      </div>
      <button className="btn-ghost text-xs py-1.5 justify-center" onClick={() => sendCommand({ cmd: "filter_suggest", key: row.key, handle: row.handle })}>
        <Icons.Sigma size={13} /> Suggest from spectrum
      </button>
      {canPerAxis && (
        <div className="flex items-center gap-2 text-xs">
          <label className="flex items-center gap-2">
            <input type="checkbox" checked={draft.perAxis} onChange={(e) => { setDirty(true); setDraft((d) => setPerAxis(d, e.target.checked)); }} />
            Per axis
          </label>
          {draft.perAxis && (
            <div className="ml-auto flex gap-0.5 p-0.5 rounded-lg bg-surface-2 border border-line">
              {AXIS_NAMES.map((n, i) => (
                <button key={n} onClick={() => setTab(i)} style={tab === i ? { color: AXIS[n] } : undefined}
                  className={`num text-[11px] px-2.5 py-0.5 rounded-md ${tab === i ? "bg-surface-3 font-semibold" : "text-faint"}`}>
                  {n}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
      <AxisFields key={draft.perAxis ? tab : "all"} value={editing} onChange={onEdit} nyq={nyq} />
      {stale && (
        <div className="text-[11px] text-warn flex items-center gap-2">
          The running filter changed on the server. Apply replaces it with these settings.
          <button className="ml-auto text-accent whitespace-nowrap" onClick={reload}>Reload</button>
        </div>
      )}
      {(!check.ok || error) && <div className="text-[11px] text-warn">{error || check.message}</div>}
      <div className="flex gap-2 justify-end">
        {active && <button className="btn-ghost text-xs py-1.5" onClick={() => { clearFilterError(row.key); setDirty(false); setStale(false); sendCommand({ cmd: "filter_clear", key: row.key }); }}>Clear filter</button>}
        <button className="btn-accent text-xs py-1.5 disabled:opacity-40" disabled={!check.ok}
          onClick={() => { clearFilterError(row.key); setDirty(false); setStale(false); sendCommand({ cmd: "filter_set", key: row.key, handle: row.handle, config: cfg }); }}>Apply</button>
      </div>
      <Findings report={report} />
    </aside>
  );
}

// The filter side pane: docked right of the panel grid (which narrows to make room) while a panel's
// Filter button has opened it; a full-width overlay on narrow windows. One pane at a time.
export default function FilterPane() {
  const pane = useFilterPane();
  if (!pane) return null;
  return <PaneBody key={pane.row.key} row={pane.row} />;
}
