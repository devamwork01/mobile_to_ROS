import { lazy, Suspense, useState } from "react";
import Panel, { IconBtn, LiveValues, StatusBadge, Segmented, NotStreaming } from "./Panel.jsx";
import { layout, WINDOWS } from "../../lib/layout.js";
import { seriesSpec } from "./seriesSpec.js";
import InsightFooter from "./InsightFooter.jsx";

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

  const viewSwitch = (
    <Segmented options={[["signal", "Signal"], ["spectrum", "Spectrum"]]} value={view} onChange={setView} />
  );
  const tools = spectrum ? viewSwitch : (
    <>
      {viewSwitch}
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
    <Panel
      title={row.label}
      unit={row.unit}
      live={state === "live" ? <LiveValues handle={row.handle} kind={row.kind} /> : null}
      badge={<StatusBadge state={state} />}
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
            <SpectrumGraph handle={row.handle} labels={spec.labels} />
          ) : (
            <SensorGraph handle={row.handle} kind={row.kind} window={pin.window} paused={pin.paused} show={show} />
          )}
        </Suspense>
      )}
    </Panel>
  );
}
