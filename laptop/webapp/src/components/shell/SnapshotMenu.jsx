import { useEffect, useRef, useState } from "react";
import { Icons } from "../../icons.js";
import { history } from "../../telemetry/liveHistory.js";
import { useLayout } from "../../lib/layout.js";
import { PIN_3D, resolveKey } from "../../telemetry/sensors.js";
import { snapshotFilename, downloadText } from "../../lib/snapshot.js";
import { Segmented } from "./Panel.jsx";

export default function SnapshotMenu({ meta, rows }) {
  const L = useLayout();
  const [open, setOpen] = useState(false);
  const [range, setRange] = useState(30);
  const [scope, setScope] = useState("pinned");
  const box = useRef(null);
  const lastDevice = useRef(null);
  const names = useRef(new Map()); // handle -> label, kept after a disconnect clears the catalog
  if (meta.device) lastDevice.current = meta.device;
  rows.forEach((r) => names.current.set(r.handle, r.label));

  useEffect(() => {
    if (!open) return;
    const onDown = (e) => box.current && !box.current.contains(e.target) && setOpen(false);
    const onKey = (e) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const empty = history.handles().length === 0;

  function download() {
    const handles =
      scope === "pinned"
        ? L.pins.filter((p) => p.key !== PIN_3D).map((p) => resolveKey(p.key, rows)?.handle).filter((h) => h != null)
        : meta.active.length
        ? meta.active.map((a) => a.handle)
        : history.handles();
    const csv = history.snapshotCsv({ handles, seconds: range, names: names.current, device: lastDevice.current });
    downloadText(snapshotFilename(lastDevice.current?.model, new Date()), csv);
    setOpen(false);
  }

  return (
    <div ref={box} className="relative">
      <button
        disabled={empty}
        onClick={() => setOpen((o) => !o)}
        title={empty ? "No data yet" : "Download recent data as CSV"}
        className="btn-ghost text-xs py-1.5 disabled:opacity-40"
      >
        <Icons.Download size={14} /> Snapshot
      </button>
      {open && (
        <div className="absolute right-0 top-full mt-2 z-30 w-64 panel p-3 flex flex-col gap-3 shadow-card">
          <div>
            <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Range</div>
            <Segmented options={[[10, "10 s"], [30, "30 s"], [60, "60 s"]]} value={range} onChange={setRange} />
          </div>
          <div>
            <div className="text-[10px] uppercase tracking-[0.12em] text-faint mb-1">Sensors</div>
            <Segmented options={[["pinned", "Pinned"], ["all", "All streaming"]]} value={scope} onChange={setScope} />
          </div>
          <p className="text-[11px] text-faint leading-snug">Display-rate data from this page. Use Record for the full-rate, lossless capture.</p>
          <button onClick={download} className="btn-accent text-xs py-1.5">
            <Icons.Download size={14} /> Download CSV
          </button>
        </div>
      )}
    </div>
  );
}
