import { lazy, Suspense } from "react";
import Panel, { IconBtn, StatusBadge, NotStreaming } from "./Panel.jsx";
import { useSignal } from "../../telemetry/store.js";
import { history } from "../../telemetry/liveHistory.js";
import { ON_CHANGE_TYPES } from "../../telemetry/history.js";
import { layout, WINDOWS } from "../../lib/layout.js";
import { minSpanFor } from "./seriesSpec.js";

const SensorGraph = lazy(() => import("./SensorGraph.jsx"));

const ONE = [true];

function LiveValue({ handle }) {
  const rec = useSignal(handle);
  const v = rec?.v?.[0] ?? history.latest(handle)?.v?.[0];
  return (
    <span className="whitespace-nowrap">
      <span className="num text-lg font-semibold">{Number.isFinite(v) ? v.toFixed(2) : "—"}</span>
    </span>
  );
}

// Single-value sensors (pressure, light, temperature, ...): a real time-axis graph like the
// vector panels, with a minimum y span so quantization noise isn't blown up to full height, and
// step drawing for on-change sensors (light, proximity) that hold their value between reports.
export default function ScalarPanel({ pin, row, state, maximized }) {
  const nextWindow = () => WINDOWS[(WINDOWS.indexOf(pin.window) + 1) % WINDOWS.length];
  const tools = (
    <>
      <button
        title="Time window"
        onClick={() => layout.setWindow(pin.key, nextWindow())}
        className="num text-[11px] px-1.5 h-7 rounded-lg text-muted hover:text-fg hover:bg-surface-3"
      >
        {pin.window}s
      </button>
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
      live={state === "live" ? <LiveValue handle={row.handle} /> : null}
      badge={<StatusBadge state={state} />}
      tools={state === "off" ? null : tools}
      collapsed={pin.collapsed && !maximized}
      maximized={maximized}
      onCollapse={() => layout.toggleCollapsed(pin.key)}
      onMaximize={() => layout.maximize(maximized ? null : pin.key)}
      onClose={() => layout.unpin(pin.key)}
      className="flex-1"
      bodyClassName={maximized ? "" : "h-52"}
    >
      {state === "off" ? (
        <NotStreaming />
      ) : (
        <Suspense fallback={<div className="h-full grid place-items-center text-xs text-faint">Loading graph…</div>}>
          <SensorGraph
            handle={row.handle}
            kind="scalar"
            window={pin.window}
            paused={pin.paused}
            show={ONE}
            minSpan={minSpanFor(row.unit)}
            stepped={ON_CHANGE_TYPES.has(row.type)}
          />
        </Suspense>
      )}
    </Panel>
  );
}
