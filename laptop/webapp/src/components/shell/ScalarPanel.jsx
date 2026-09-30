import { useEffect, useState } from "react";
import Panel, { StatusBadge, NotStreaming } from "./Panel.jsx";
import Sparkline from "./Sparkline.jsx";
import { useSignal } from "../../telemetry/store.js";
import { history } from "../../telemetry/liveHistory.js";
import { layout } from "../../lib/layout.js";

function ScalarBody({ handle, unit }) {
  const rec = useSignal(handle);
  const [vals, setVals] = useState([]);
  useEffect(() => {
    const read = () => {
      const a = history.read(handle, 60).v[0];
      if (!a) return setVals([]);
      const step = Math.max(1, Math.ceil(a.length / 120));
      const out = [];
      for (let i = 0; i < a.length; i += step) out.push(a[i]);
      setVals(out);
    };
    read();
    const id = setInterval(read, 500);
    return () => clearInterval(id);
  }, [handle]);
  const v = rec?.v?.[0] ?? history.latest(handle)?.v?.[0];
  return (
    <div className="h-full flex items-end justify-between gap-4 p-4">
      <div className="whitespace-nowrap">
        <span className="num text-4xl font-semibold">{Number.isFinite(v) ? v.toFixed(2) : "—"}</span>{" "}
        <span className="text-sm text-faint">{unit}</span>
      </div>
      <Sparkline values={vals} width={160} height={44} />
    </div>
  );
}

export default function ScalarPanel({ pin, row, state, maximized }) {
  return (
    <Panel
      title={row.label}
      unit={row.unit}
      badge={<StatusBadge state={state} />}
      collapsed={pin.collapsed && !maximized}
      maximized={maximized}
      onCollapse={() => layout.toggleCollapsed(pin.key)}
      onMaximize={() => layout.maximize(maximized ? null : pin.key)}
      onClose={() => layout.unpin(pin.key)}
      className="flex-1"
      bodyClassName={maximized ? "" : "h-28"}
    >
      {state === "off" ? <NotStreaming /> : <ScalarBody handle={row.handle} unit={row.unit} />}
    </Panel>
  );
}
