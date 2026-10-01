import { useEffect, useState } from "react";
import { createPortal } from "react-dom";
import { Icons } from "../../icons.js";
import { sendCommand } from "../../telemetry/store.js";
import { useFilterConfig, useFilterSuggestion, useFilterError, clearFilterFeedback } from "../../telemetry/insights.js";
import { validateConfig, configToDraft, draftToConfig, MAX_NOTCHES } from "../../lib/filterConfig.js";

// Edit one sensor's filter. "Suggest" asks the server to propose a config from the live spectrum;
// Apply / Clear go to the server, which owns the truth and broadcasts it to every dashboard.
export default function FilterEditor({ row, fs, onClose }) {
  const active = useFilterConfig(row.key);
  const suggestion = useFilterSuggestion(row.key);
  const error = useFilterError(row.key);
  const [draft, setDraft] = useState(() => configToDraft(active));
  useEffect(() => { if (suggestion) setDraft(configToDraft(suggestion)); }, [suggestion]);
  useEffect(() => () => clearFilterFeedback(row.key), [row.key]);
  useEffect(() => {
    const k = (e) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", k);
    return () => document.removeEventListener("keydown", k);
  }, [onClose]);
  const cfg = draftToConfig(draft);
  const check = validateConfig(cfg, fs);
  const nyq = fs ? 0.45 * fs : 50;
  const set = (patch) => setDraft((d) => ({ ...d, ...patch }));
  const setNotch = (i, patch) => setDraft((d) => ({ ...d, notches: d.notches.map((n, j) => (j === i ? { ...n, ...patch } : n)) }));
  const logPos = (hz) => Math.log10(Math.max(0.1, hz) / 0.1) / Math.log10(nyq / 0.1);
  const fromPos = (p) => +(0.1 * Math.pow(nyq / 0.1, p)).toPrecision(3);

  return createPortal(
    <div className="fixed inset-0 z-40" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="panel absolute right-4 top-16 w-[360px] max-w-[92vw] p-4 flex flex-col gap-3 shadow-card">
        <div className="flex items-center gap-2">
          <Icons.Filter size={15} className="text-accent" />
          <h2 className="text-sm font-semibold">Filter · {row.label}</h2>
          <span className="ml-auto text-[11px] text-faint num">{fs ? `${fs.toFixed(1)} Hz sampling` : "rate unknown"}</span>
        </div>
        <button className="btn-ghost text-xs py-1.5 justify-center" onClick={() => sendCommand({ cmd: "filter_suggest", key: row.key, handle: row.handle })}>
          <Icons.Sigma size={13} /> Suggest from spectrum
        </button>
        <label className="flex items-center gap-2 text-xs">
          <input type="checkbox" checked={draft.lpOn} onChange={(e) => set({ lpOn: e.target.checked })} />
          Low-pass (Butterworth)
          <select value={draft.order} disabled={!draft.lpOn} onChange={(e) => set({ order: Number(e.target.value) })}
            className="ml-auto bg-surface-2 border border-line rounded-md text-xs px-1 py-0.5">
            <option value={2}>order 2</option><option value={4}>order 4</option>
          </select>
        </label>
        {draft.lpOn && (
          <div className="flex items-center gap-2">
            <input type="range" min={0} max={1} step={0.001} value={logPos(draft.lpHz)} onChange={(e) => set({ lpHz: fromPos(Number(e.target.value)) })} className="flex-1 accent-[rgb(var(--accent))]" />
            <input type="number" min={0.1} step={0.1} value={draft.lpHz} onChange={(e) => set({ lpHz: Number(e.target.value) })}
              className="w-20 bg-surface-2 border border-line rounded-md text-xs px-2 py-1 num" /> <span className="text-xs text-faint">Hz</span>
          </div>
        )}
        <div className="flex flex-col gap-1.5">
          <div className="flex items-center text-xs">
            Notches
            <button disabled={draft.notches.length >= MAX_NOTCHES} className="ml-auto text-accent text-xs disabled:opacity-40"
              onClick={() => set({ notches: [...draft.notches, { hz: 8, q: 10 }] })}>+ add</button>
          </div>
          {draft.notches.map((n, i) => (
            <div key={i} className="flex items-center gap-2 text-xs">
              <input type="number" step={0.1} value={n.hz} onChange={(e) => setNotch(i, { hz: Number(e.target.value) })} className="w-20 bg-surface-2 border border-line rounded-md px-2 py-1 num" /> Hz
              <span className="text-faint">Q</span>
              <input type="number" step={1} value={n.q} onChange={(e) => setNotch(i, { q: Number(e.target.value) })} className="w-16 bg-surface-2 border border-line rounded-md px-2 py-1 num" />
              <button className="ml-auto text-faint hover:text-fg" title="Remove" onClick={() => set({ notches: draft.notches.filter((_, j) => j !== i) })}><Icons.X size={13} /></button>
            </div>
          ))}
        </div>
        {(!check.ok || error) && <div className="text-[11px] text-warn">{error || check.message}</div>}
        <div className="flex gap-2 justify-end">
          {active && <button className="btn-ghost text-xs py-1.5" onClick={() => { sendCommand({ cmd: "filter_clear", key: row.key }); onClose(); }}>Clear filter</button>}
          <button className="btn-accent text-xs py-1.5 disabled:opacity-40" disabled={!check.ok}
            onClick={() => { sendCommand({ cmd: "filter_set", key: row.key, handle: row.handle, config: cfg }); onClose(); }}>Apply</button>
        </div>
      </div>
    </div>,
    document.body
  );
}
