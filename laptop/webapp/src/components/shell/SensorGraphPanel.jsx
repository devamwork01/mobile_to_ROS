import { lazy, Suspense, useState } from "react";
import Panel, { IconBtn, LiveValues, StatusBadge, Segmented, NotStreaming } from "./Panel.jsx";
import { layout, WINDOWS } from "../../lib/layout.js";
import { seriesSpec } from "./seriesSpec.js";
import InsightFooter from "./InsightFooter.jsx";
import FilterEditor from "./FilterEditor.jsx";
import { useFilterConfig, useFilterSuggestion, useFilterError, useFilterErrorRunning, useInsightStats } from "../../telemetry/insights.js";
import { FILTERABLE, filterBadge } from "../../lib/filterConfig.js";

const SensorGraph = lazy(() => import("./SensorGraph.jsx"));
// Server-computed (full-rate) spectrum; shares the uPlot chunk with SensorGraph.
const SpectrumGraph = lazy(() => import("./SpectrumGraph.jsx"));

function nextWindow(w) {
  const i = WINDOWS.indexOf(w);
  return WINDOWS[(i + 1) % WINDOWS.length];
}

export default function SensorGraphPanel({ pin, row, state, maximized }) {
  const spec = seriesSpec(row.kind);
  const labels = [...spec.labels, ...(spec.mag ? ["|v|"] : [])];
  const [show, setShow] = useState(() => labels.map((l) => l !== "|v|"));
  const toggle = (i) => setShow((s) => s.map((on, j) => (j === i ? !on : on)));
  const [view, setView] = useState("signal"); // "signal" | "spectrum"
  const spectrum = view === "spectrum";
  const filterable = FILTERABLE(row.type, row.kind);
  const fcfgSet = useFilterConfig(row.key);
  const ferror = useFilterError(row.key);
  const frunning = useFilterErrorRunning(row.key);
  const badge = filterBadge(fcfgSet, ferror, frunning);
  const fcfg = fcfgSet && (!ferror || frunning) ? fcfgSet : null; // only treat it as filtered while it actually runs
  const suggestion = useFilterSuggestion(row.key);
  const fs = useInsightStats(row.handle)?.rate_hz || null;
  const [editing, setEditing] = useState(false);
  const [trace, setTrace] = useState("both"); // raw | filtered | both (only with an active filter)

  const viewSwitch = (
    <Segmented options={[["signal", "Signal"], ["spectrum", "Spectrum"]]} value={view} onChange={setView} />
  );
  const filterBtn = filterable ? (
    <IconBtn title={fcfgSet ? "Filter - edit" : "Filter"} icon="Filter" active={!!fcfgSet} onClick={() => setEditing(true)} />
  ) : null;
  const tools = spectrum ? (
    <>
      {filterBtn}
      {viewSwitch}
    </>
  ) : (
    <>
      {filterBtn}
      {viewSwitch}
      {fcfg && maximized && (
        <Segmented options={[["raw", "Raw"], ["filtered", "Filtered"], ["both", "Both"]]} value={trace} onChange={setTrace} />
      )}
      {maximized ? (
        <>
          <Segmented options={WINDOWS.map((w) => [w, `${w}s`])} value={pin.window} onChange={(w) => layout.setWindow(pin.key, w)} />
          <div className="flex gap-0.5 p-0.5 rounded-lg bg-surface-2 border border-line">
            {labels.map((l, i) => (
              <button
                key={l}
                onClick={() => toggle(i)}
                className={`num text-[11px] px-2 py-0.5 rounded-md ${show[i] ? "bg-surface-3 text-fg" : "text-faint"}`}
              >
                {l}
              </button>
            ))}
          </div>
        </>
      ) : (
        <button
          title="Time window"
          onClick={() => layout.setWindow(pin.key, nextWindow(pin.window))}
          className="num text-[11px] px-1.5 h-7 rounded-lg text-muted hover:text-fg hover:bg-surface-3"
        >
          {pin.window}s
        </button>
      )}
      <IconBtn
        title={pin.paused ? "Resume" : "Pause"}
        icon={pin.paused ? "Play" : "Pause"}
        active={pin.paused}
        onClick={() => layout.setPaused(pin.key, !pin.paused)}
      />
    </>
  );

  return (
    <>
    <Panel
      title={row.label}
      unit={row.unit}
      live={state === "live" ? <LiveValues handle={row.handle} kind={row.kind} /> : null}
      badge={
        <>
          <StatusBadge state={state} />
          {badge && (
            <span title={badge.title} className={`text-[10px] px-1.5 py-0.5 rounded-md whitespace-nowrap ${badge.tone === "warn" ? "bg-warn/15 text-warn" : "bg-accent-soft text-accent"}`}>
              {badge.label}
            </span>
          )}
        </>
      }
      tools={state === "off" ? null : tools}
      collapsed={pin.collapsed && !maximized}
      maximized={maximized}
      onCollapse={() => layout.toggleCollapsed(pin.key)}
      onMaximize={() => layout.maximize(maximized ? null : pin.key)}
      onClose={() => layout.unpin(pin.key)}
      className="flex-1"
      bodyClassName={maximized ? "" : "h-52"}
      footer={state === "off" ? null : <InsightFooter handle={row.handle} n={spec.n} />}
    >
      {state === "off" ? (
        <NotStreaming />
      ) : (
        <Suspense fallback={<div className="h-full grid place-items-center text-xs text-faint">Loading graph…</div>}>
          {spectrum ? (
            <SpectrumGraph handle={row.handle} labels={spec.labels} filter={fcfg || null} fs={fs} suggestion={editing ? suggestion || null : null} />
          ) : (
            <SensorGraph handle={row.handle} kind={row.kind} window={pin.window} paused={pin.paused} show={show} filtered={!!fcfg} trace={trace} />
          )}
        </Suspense>
      )}
    </Panel>
    {editing && <FilterEditor row={row} fs={fs} onClose={() => setEditing(false)} />}
    </>
  );
}
