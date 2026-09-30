import { useEffect } from "react";
import SensorPicker from "./SensorPicker.jsx";
import PanelGrid from "./PanelGrid.jsx";
import { layout, useLayout } from "../../lib/layout.js";
import { defaultPinKeys } from "../../telemetry/sensors.js";

export default function Dashboard({ meta, rows, onInfo }) {
  const L = useLayout();
  // Seed defaults only once the phone's catalog is known (vendor-named keys); a device-less
  // --selftest stream has no catalog, so it seeds from the generic names.
  const catalogReady = meta.catalog.length > 0 || !meta.device;
  useEffect(() => {
    if (catalogReady) layout.seedDefaults(defaultPinKeys(rows));
  }, [rows, catalogReady]);
  return (
    <div className="flex h-full min-h-0">
      {!L.maximized && <SensorPicker rows={rows} onInfo={onInfo} />}
      <div className="flex-1 min-w-0 overflow-auto">
        <PanelGrid meta={meta} rows={rows} />
      </div>
    </div>
  );
}
