// Dashboard layout: which panels are pinned and how each one is shown. Persisted to
// localStorage (every access guarded: a private window or blocked storage must not break the
// page), except `maximized`, which always starts restored.
import { useSyncExternalStore } from "react";
import { PIN_3D } from "../telemetry/sensors.js";

export const LAYOUT_KEY = "ss.layout.v1";
export const WINDOWS = [5, 10, 30, 60];
export const DEFAULT_WINDOW = 10;

const pinOf = (key) => ({ key, window: DEFAULT_WINDOW, paused: false, collapsed: false });

export function defaultLayout() {
  return { v: 1, pins: [pinOf(PIN_3D)], maximized: null, pickerCollapsed: false, seeded: false };
}

export function loadLayout(storage) {
  try {
    const raw = storage?.getItem(LAYOUT_KEY);
    if (!raw) return defaultLayout();
    const s = JSON.parse(raw);
    if (!s || s.v !== 1 || !Array.isArray(s.pins)) return defaultLayout();
    const seen = new Set();
    const pins = [];
    for (const p of s.pins) {
      if (!p || typeof p.key !== "string" || seen.has(p.key)) continue;
      seen.add(p.key);
      pins.push({
        key: p.key,
        window: WINDOWS.includes(p.window) ? p.window : DEFAULT_WINDOW,
        paused: !!p.paused,
        collapsed: !!p.collapsed,
      });
    }
    return { v: 1, pins, maximized: null, pickerCollapsed: !!s.pickerCollapsed, seeded: !!s.seeded };
  } catch {
    return defaultLayout();
  }
}

export function createLayoutStore(storage) {
  let state = loadLayout(storage);
  const listeners = new Set();
  const has = (key) => state.pins.some((p) => p.key === key);

  function set(next) {
    state = next;
    try {
      const { maximized, ...persisted } = state; // eslint-disable-line no-unused-vars
      storage?.setItem(LAYOUT_KEY, JSON.stringify(persisted));
    } catch {
      /* storage unavailable: keep working in memory */
    }
    listeners.forEach((l) => l());
  }
  const mapPin = (key, f) => set({ ...state, pins: state.pins.map((p) => (p.key === key ? { ...p, ...f(p) } : p)) });

  return {
    get: () => state,
    subscribe(cb) {
      listeners.add(cb);
      return () => listeners.delete(cb);
    },
    pin(key) {
      if (!has(key)) set({ ...state, pins: [...state.pins, pinOf(key)] });
    },
    unpin(key) {
      if (!has(key)) return;
      set({ ...state, pins: state.pins.filter((p) => p.key !== key), maximized: state.maximized === key ? null : state.maximized });
    },
    toggleCollapsed(key) {
      mapPin(key, (p) => ({ collapsed: !p.collapsed }));
    },
    setWindow(key, seconds) {
      if (WINDOWS.includes(seconds)) mapPin(key, () => ({ window: seconds }));
    },
    setPaused(key, paused) {
      mapPin(key, () => ({ paused: !!paused }));
    },
    maximize(key) {
      if (key !== null && !has(key)) return;
      if (state.maximized !== key) set({ ...state, maximized: key });
    },
    togglePicker() {
      set({ ...state, pickerCollapsed: !state.pickerCollapsed });
    },
    // First run only: once live vector sensors appear, pin the first two next to the 3D view.
    seedDefaults(keys) {
      if (state.seeded || keys.length === 0) return;
      const add = keys.filter((k) => !has(k)).slice(0, 2).map(pinOf);
      set({ ...state, seeded: true, pins: [...state.pins, ...add] });
    },
  };
}

function browserStorage() {
  try {
    return typeof localStorage !== "undefined" ? localStorage : null;
  } catch {
    return null;
  }
}

export const layout = createLayoutStore(browserStorage());

export function useLayout() {
  return useSyncExternalStore(layout.subscribe, layout.get, layout.get);
}
