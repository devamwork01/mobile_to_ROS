import { useEffect } from "react";
import SensorPicker from "./SensorPicker.jsx";
import PanelGrid from "./PanelGrid.jsx";
import { layout, useLayout } from "../../lib/layout.js";
import { defaultPinKeys } from "../../telemetry/sensors.js";

export default function Dashboard({ meta, rows, onInfo }) {
  const L = useLayout();
  useEffect(() => {
    layout.seedDefaults(defaultPinKeys(rows));
  }, [rows]);
  return (
    <div className="flex h-full min-h-0">
      {!L.maximized && <SensorPicker rows={rows} onInfo={onInfo} />}
      <div className="flex-1 min-w-0 overflow-auto">
        <PanelGrid meta={meta} rows={rows} />
      </div>
    </div>
  );
}
