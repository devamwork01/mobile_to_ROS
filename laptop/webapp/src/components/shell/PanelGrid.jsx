import { Fragment, useEffect } from "react";
import { layout, useLayout } from "../../lib/layout.js";
import { PIN_3D, resolveKey, keyLabel } from "../../telemetry/sensors.js";
import Phone3DPanel from "./Phone3DPanel.jsx";
import SensorGraphPanel from "./SensorGraphPanel.jsx";
import ScalarPanel from "./ScalarPanel.jsx";
import Panel, { StatusBadge, NotStreaming } from "./Panel.jsx";
import TestRunPanel from "./TestRunPanel.jsx";

// "live" | "stale" (phone gone, history kept) | "off" (sensor not streaming / not on this phone)
function stateOf(row, deviceConnected) {
  if (!row || !row.active) return deviceConnected || !row ? "off" : "stale";
  return deviceConnected ? "live" : "stale";
}

function renderItem({ pin, row }, maximized, connected) {
  if (pin.key === PIN_3D) return <Phone3DPanel pin={pin} maximized={maximized} />;
  const state = stateOf(row, connected);
  if (!row) {
    return (
      <Panel
        title={keyLabel(pin.key)}
        badge={<StatusBadge state="off" />}
        collapsed={pin.collapsed && !maximized}
        onCollapse={() => layout.toggleCollapsed(pin.key)}
        onClose={() => layout.unpin(pin.key)}
        bodyClassName="h-28"
      >
        <NotStreaming />
      </Panel>
    );
  }
  const P = row.kind === "scalar" ? ScalarPanel : SensorGraphPanel;
  return <P pin={pin} row={row} state={state} maximized={maximized} />;
}

export default function PanelGrid({ meta, rows }) {
  const L = useLayout();
  // Data flowing counts as connected: --selftest streams without a device.
  const connected = !!meta.device || meta.active.length > 0;

  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "Escape" && layout.get().maximized) layout.maximize(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Pins resolve by name, then by sensor type (a layout made with another phone). Two pins that
  // land on the same sensor show one panel.
  const seen = new Set();
  const items = [];
  for (const pin of L.pins) {
    const row = pin.key === PIN_3D ? null : resolveKey(pin.key, rows);
    if (row) {
      if (seen.has(row.handle)) continue;
      seen.add(row.handle);
    }
    items.push({ pin, row });
  }

  const max = L.maximized && items.find((i) => i.pin.key === L.maximized);
  if (max) {
    return (
      <div className="h-full p-3 flex flex-col gap-3">
        <TestRunPanel rows={rows} />
        {renderItem(max, true, connected)}
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="h-full flex flex-col p-3 gap-3">
        <TestRunPanel rows={rows} />
        <div className="flex-1 grid place-items-center text-center text-sm text-faint p-6">
          Nothing pinned. Pin a sensor from the list on the left.
        </div>
      </div>
    );
  }

  const is3d = (i) => i.pin.key === PIN_3D;
  const ordered = [
    ...items.filter((i) => is3d(i) && !i.pin.collapsed),
    ...items.filter((i) => !is3d(i) && !i.pin.collapsed),
    ...items.filter((i) => i.pin.collapsed),
  ];
  return (
    <div className="grid grid-cols-1 xl:grid-cols-2 grid-flow-row-dense gap-3 p-3 content-start">
      <TestRunPanel rows={rows} />
      {ordered.map((it) => (
        <Fragment key={it.pin.key}>
          <div className={`min-w-0 flex flex-col ${it.pin.collapsed ? "xl:col-span-2" : is3d(it) ? "xl:row-span-2" : ""}`}>
            {renderItem(it, false, connected)}
          </div>
        </Fragment>
      ))}
    </div>
  );
}
