import { lazy, Suspense, useEffect, useState } from "react";
import Panel from "./Panel.jsx";
import { getByType } from "../../telemetry/store.js";
import { eulerFromQuat } from "../../lib/orient.js";
import { layout } from "../../lib/layout.js";
import { PIN_3D } from "../../telemetry/sensors.js";

const Phone3D = lazy(() => import("../Phone3D.jsx"));
const ORI_TYPES = [11, 15, 20];

function useEuler() {
  const [e, setE] = useState(null);
  useEffect(() => {
    const id = setInterval(() => {
      for (const ty of ORI_TYPES) {
        const r = getByType(ty);
        if (r?.v?.length >= 3) {
          const [x, y, z] = r.v;
          const w = r.v.length >= 4 && r.v[3] != null ? r.v[3] : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
          setE(eulerFromQuat(x, y, z, w));
          return;
        }
      }
      setE(null);
    }, 100);
    return () => clearInterval(id);
  }, []);
  return e;
}

function Readouts() {
  const e = useEuler();
  return (
    <div className="grid grid-cols-3 gap-2 px-3 pb-3">
      {[["Roll", "roll"], ["Pitch", "pitch"], ["Yaw", "yaw"]].map(([label, k]) => (
        <div key={k} className="rounded-lg bg-surface-2 px-2.5 py-1.5">
          <div className="text-[9px] uppercase tracking-[0.12em] text-faint">{label}</div>
          <div className="num text-base">{e ? `${e[k].toFixed(1)}°` : "—"}</div>
        </div>
      ))}
    </div>
  );
}

export default function Phone3DPanel({ pin, maximized }) {
  return (
    <Panel
      title="3D Orientation"
      unit="rotation vector"
      collapsed={pin.collapsed && !maximized}
      maximized={maximized}
      onCollapse={() => layout.toggleCollapsed(PIN_3D)}
      onMaximize={() => layout.maximize(maximized ? null : PIN_3D)}
      onClose={() => layout.unpin(PIN_3D)}
      className="flex-1"
      bodyClassName="flex flex-col"
    >
      <div className={`flex-1 p-3 ${maximized ? "" : "min-h-[400px]"}`}>
        <Suspense fallback={<div className="h-full grid place-items-center text-xs text-faint">Loading 3D…</div>}>
          <Phone3D />
        </Suspense>
      </div>
      <Readouts />
    </Panel>
  );
}
