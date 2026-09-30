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

// A pin made before the phone's catalog arrived is keyed by the generic signal name
// ("<type>:<label>"); it still matches the row once the vendor name is known.
const aliasKey = (r) => sensorKey(r.type, r.label);
const matches = (key, r) => r.key === key || aliasKey(r) === key;

/** The pin key (from `pinKeys`) that shows this row, or null when it is not pinned. */
export function pinKeyFor(row, pinKeys, rows) {
  for (const k of pinKeys) if (k !== PIN_3D && resolveKey(k, rows) === row) return k;
  return null;
}

const keyType = (key) => Number(key.slice(0, key.indexOf(":")));

// Best row for a pin: exact (or pre-catalog alias) match first; otherwise this phone's sensor of
// the same type, so a layout made with one phone carries over to another. Active rows and then
// the lowest handle win ties.
export function resolveKey(key, rows) {
  const better = (r, best) => !best || (r.active && !best.active) || (r.active === best.active && r.handle < best.handle);
  let exact = null;
  let sameType = null;
  const type = keyType(key);
  for (const r of rows) {
    if (matches(key, r)) {
      if (better(r, exact)) exact = r;
    } else if (r.type === type && better(r, sameType)) sameType = r;
  }
  return exact || sameType;
}

export function defaultPinKeys(rows) {
  const rank = (t) => (PREFERRED.includes(t) ? PREFERRED.indexOf(t) : PREFERRED.length);
  return rows
    .filter((r) => r.active && r.kind === "vector")
    .sort((a, b) => rank(a.type) - rank(b.type) || a.handle - b.handle)
    .map((r) => r.key)
    .filter((k, i, a) => a.indexOf(k) === i);
}
