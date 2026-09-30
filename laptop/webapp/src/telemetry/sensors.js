// Picker rows and pin keys. A pin is remembered by "<type>:<catalogName>" rather than the
// handle, because the phone can assign different handles after a reconnect.
import { signalMeta } from "./signals.js";

export const PIN_3D = "3d";
const PREFERRED = [1, 4, 2, 9, 10]; // accel, gyro, mag, gravity, linear accel

export const sensorKey = (type, name) => `${type}:${name}`;

export function keyLabel(key) {
  if (key === PIN_3D) return "3D Orientation";
  const i = key.indexOf(":");
  return i < 0 ? key : key.slice(i + 1);
}

function row(handle, type, stringType, catalogName, active) {
  const m = signalMeta(type, stringType);
  return {
    handle,
    type,
    stringType,
    key: sensorKey(type, catalogName || m.name),
    label: m.name,
    detail: catalogName || m.sub,
    kind: m.kind,
    unit: m.unit,
    active,
  };
}

export function listSensors(catalog = [], active = []) {
  const activeType = new Map(active.map((a) => [a.handle, a.type]));
  const rows = [];
  const seen = new Set();
  for (const c of catalog) {
    rows.push(row(c.handle, activeType.get(c.handle) ?? c.type, c.stringType, c.name, activeType.has(c.handle)));
    seen.add(c.handle);
  }
  for (const [handle, type] of activeType) if (!seen.has(handle)) rows.push(row(handle, type, undefined, null, true));
  return rows.sort((a, b) => a.label.localeCompare(b.label) || a.handle - b.handle);
}

export function resolveKey(key, rows) {
  let best = null;
  for (const r of rows) {
    if (r.key !== key) continue;
    if (!best || (r.active && !best.active) || (r.active === best.active && r.handle < best.handle)) best = r;
  }
  return best;
}

export function defaultPinKeys(rows) {
  const rank = (t) => (PREFERRED.includes(t) ? PREFERRED.indexOf(t) : PREFERRED.length);
  return rows
    .filter((r) => r.active && r.kind === "vector")
    .sort((a, b) => rank(a.type) - rank(b.type) || a.handle - b.handle)
    .map((r) => r.key)
    .filter((k, i, a) => a.indexOf(k) === i);
}
