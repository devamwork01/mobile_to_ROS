import { useEffect, useMemo, useRef, useState, lazy, Suspense } from "react";
import "./telemetry/liveHistory.js"; // start filling history before any panel mounts
import IconRail from "./components/shell/IconRail.jsx";
import KpiStrip from "./components/shell/KpiStrip.jsx";
import SnapshotMenu from "./components/shell/SnapshotMenu.jsx";
import Dashboard from "./components/shell/Dashboard.jsx";
import SensorModal from "./components/SensorModal.jsx";
import PerfOverlay from "./components/PerfOverlay.jsx";
import ThemeToggle from "./components/ThemeToggle.jsx";
import { getStoredTheme, setTheme } from "./lib/theme.js";
import { netLoss } from "./lib/metrics.js";
import { layout } from "./lib/layout.js";
import { useTelemetry } from "./telemetry/store.js";
import { listSensors } from "./telemetry/sensors.js";
import { useOpenReportRequest } from "./telemetry/insights.js";

const RecordingsView = lazy(() => import("./components/RecordingsView.jsx"));
const InsightsView = lazy(() => import("./components/InsightsView.jsx"));

function Loading({ label = "Loading…" }) {
  return <div className="min-h-[200px] grid place-items-center text-sm text-faint">{label}</div>;
}

function Section({ title, children, className = "" }) {
  return (
    <section className={`panel p-4 ${className}`}>
      {title && <h2 className="text-[11px] font-semibold uppercase tracking-[0.12em] text-muted mb-3">{title}</h2>}
      {children}
    </section>
  );
}

function DiagRow({ label, value, tone }) {
  return (
    <div className="flex items-center justify-between py-2 border-b border-line/60 last:border-0">
      <span className="text-sm text-muted">{label}</span>
      <span className={`num text-sm ${tone || "text-fg"}`}>{value}</span>
    </div>
  );
}

function Diagnostics({ meta }) {
  const d = meta.debug || {};
  const s = meta.stats || {};
  const rate = s.bps ? (s.bps < 1048576 ? `${(s.bps / 1024).toFixed(0)} KB/s` : `${(s.bps / 1048576).toFixed(2)} MB/s`) : "—";
  const inN = s.ui_records_in, outN = s.ui_records_out;
  const coalesced = inN ? Math.round((1 - outN / inN) * 100) : null;
  return (
    <div className="grid md:grid-cols-2 gap-4">
      <Section title="Connection">
        <DiagRow label="Status" value={meta.device ? "Connected" : "No device"} tone={meta.device ? "text-ok" : "text-muted"} />
        <DiagRow label="Device" value={meta.device ? `${meta.device.model} (Android ${meta.device.android})` : "—"} />
        <DiagRow label="Network latency (p50 / p95)" value={`${d.latency_ms_p50 ?? "—"} / ${d.latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Jitter" value={`${d.jitter_ms ?? "—"} ms`} />
        <DiagRow label="Phone latency (acq→send)" value={`${d.phone_latency_ms_p50 ?? "—"} / ${d.phone_latency_ms_p95 ?? "—"} ms`} />
        <DiagRow label="Packet loss (net of backfill)" value={`${netLoss(d, s).pct} %`} tone={netLoss(d, s).netLost > 0 ? "text-warn" : "text-ok"} />
        <DiagRow label="Throughput" value={rate} />
      </Section>
      <Section title="Raw pipeline (recorded losslessly)">
        <DiagRow label="Active sensors" value={meta.active.length} />
        <DiagRow label="Packets received" value={s.packets ?? "—"} />
        <DiagRow label="Records received" value={d.received ?? s.records ?? "—"} />
        <DiagRow label="Lost samples (wire)" value={d.lost ?? "—"} tone={(d.lost || 0) > 0 ? "text-warn" : "text-fg"} />
        <DiagRow label="Reordered" value={d.reordered ?? "—"} />
        <DiagRow label="Decode errors" value={s.decode_errors ?? "—"} tone={(s.decode_errors || 0) > 0 ? "text-warn" : "text-fg"} />
        <DiagRow label="Recording" value={meta.recording?.active ? `Yes · ${meta.recording.rows} rows` : "No"} />
      </Section>
      <Section title="Presentation (browser stream)">
        <DiagRow label="UI rate cap" value={s.ui_hz ? `${s.ui_hz} Hz` : "—"} />
        <DiagRow label="Coalesced" value={coalesced != null ? `${coalesced} %` : "—"} tone="text-muted" />
        <DiagRow label="Forwarded to browser" value={outN != null ? outN.toLocaleString() : "—"} />
        <DiagRow label="Raw records seen" value={inN != null ? inN.toLocaleString() : "—"} />
        <div className="text-[11px] text-faint pt-2 leading-snug">
          Coalesced = presentation updates intentionally dropped to hold the UI rate — not lost data.
          Raw is recorded in full; wire loss is recovered by backfill (see below).
        </div>
      </Section>
      <Section title="Backfill (recording integrity)">
        <DiagRow label="Backfilled records" value={(s.backfilled ?? 0).toLocaleString()} tone={(s.backfilled || 0) > 0 ? "text-ok" : "text-fg"} />
        <DiagRow label="Permanent gaps" value={s.permanent_gaps ?? 0} tone={(s.permanent_gaps || 0) > 0 ? "text-warn" : "text-ok"} />
        <div className="text-[11px] text-faint pt-2 leading-snug">
          Backfilled = samples the live UDP stream missed that the phone resent from its on-phone
          recording, merged into the session (deduped by seq). Permanent gaps = ranges the phone
          could no longer serve (aged out of its ring) — the only irrecoverable data loss.
        </div>
      </Section>
    </div>
  );
}


export default function App() {
  const meta = useTelemetry();
  const [view, setViewState] = useState("Dashboard");
  const [selected, setSelected] = useState(null);
  const [theme, setThemeState] = useState(() => getStoredTheme());
  const changeTheme = (p) => setThemeState(setTheme(p));
  const setView = (v) => {
    layout.maximize(null); // changing page restores a maximised panel
    setViewState(v);
  };
  // "Open report" (test-run panel / Recordings) jumps to Insights, which consumes the request.
  const openReq = useOpenReportRequest();
  useEffect(() => {
    if (openReq) setView("Insights");
  }, [openReq]); // eslint-disable-line react-hooks/exhaustive-deps
  const showPerf = typeof location !== "undefined" && new URLSearchParams(location.search).has("perf");
  // The server clears the catalog when the phone disconnects (and a socket blip can briefly lose
  // it while data still flows). Keep the last catalog so pins keep their names and resolve:
  // inactive rows show "stale" with their history instead of "not streaming".
  const lastCatalog = useRef([]);
  if (meta.catalog.length) lastCatalog.current = meta.catalog;
  const catalog = meta.catalog.length ? meta.catalog : lastCatalog.current;
  const rows = useMemo(() => listSensors(catalog, meta.active), [catalog, meta.active]);

  return (
    <div className="flex h-full bg-ink">
      <IconRail view={view} onView={setView} theme={theme} onTheme={changeTheme} />
      <main className="flex-1 min-w-0 flex flex-col">
        <KpiStrip meta={meta} rows={rows} snapshot={<SnapshotMenu meta={meta} rows={rows} />} />
        <div className="flex-1 min-h-0 bg-hero-grad">
          {view === "Dashboard" ? (
            <Dashboard meta={meta} rows={rows} onInfo={(r) => setSelected({ handle: r.handle, type: r.type })} />
          ) : (
            <div className="h-full overflow-auto p-4">
              <h1 className="text-sm font-semibold mb-4">{view}</h1>
              {view === "Diagnostics" && <Diagnostics meta={meta} />}
              {view === "Insights" && (
                <Suspense fallback={<Loading label="Loading insights…" />}>
                  <InsightsView />
                </Suspense>
              )}
              {view === "Recordings" && (
                <Suspense fallback={<Loading label="Loading recordings…" />}>
                  <RecordingsView />
                </Suspense>
              )}
              {view === "Settings" && (
                <div className="grid md:grid-cols-2 gap-4 max-w-3xl">
                  <Section title="Appearance">
                    <div className="flex items-center justify-between gap-4 flex-wrap">
                      <div>
                        <div className="text-sm text-fg">Theme</div>
                        <div className="text-xs text-muted">System follows your OS setting.</div>
                      </div>
                      <ThemeToggle value={theme} onChange={changeTheme} />
                    </div>
                  </Section>
                  <Section title="Connection">
                    <DiagRow label="Dashboard host" value={typeof location !== "undefined" ? location.host : "—"} />
                    <DiagRow label="Phone connected" value={meta.device ? "Yes" : "No"} tone={meta.device ? "text-ok" : "text-muted"} />
                    <DiagRow label="UI update cap" value={meta.stats?.ui_hz ? `${meta.stats.ui_hz} Hz` : "—"} />
                    <DiagRow label="Recording" value={meta.recording?.active ? `Yes · ${meta.recording.rows} rows` : "No"} />
                    <div className="text-[11px] text-faint pt-2 leading-snug">
                      Ports and capture options are set with server flags (see README). This panel is read-only.
                    </div>
                  </Section>
                </div>
              )}
            </div>
          )}
        </div>
      </main>

      {selected && <SensorModal sensor={selected} catalog={meta.catalog} onClose={() => setSelected(null)} />}
      {showPerf && <PerfOverlay />}
    </div>
  );
}
