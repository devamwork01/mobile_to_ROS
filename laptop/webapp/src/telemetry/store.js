// Read-only telemetry store. Connects to the EXISTING dashboard WebSocket and
// exposes it to React without re-rendering the whole tree at stream rate:
//  - useTelemetry(): low-frequency meta (status, stats, debug, catalog, recording, active sensors)
//  - useSignal(handle): the latest record for one sensor (high-frequency; only that leaf re-renders)
// Nothing here touches the backend/protocol — it only consumes what the server already broadcasts.
import { useSyncExternalStore } from "react";

const params = new URLSearchParams(location.search);
const WS_PORT = params.get("ws") || "8081";
const WS_URL = `ws://${location.hostname || "localhost"}:${WS_PORT}`;

let meta = {
  status: "connecting", // connecting | connected | disconnected
  stats: {},
  debug: {},
  recording: { active: false, rows: 0 },
  catalog: [], // [{handle, name, type, units, ...}] from the phone control channel
  device: null, // { model, android } | null
  active: [], // [{handle, type}] sensors currently streaming
};
const metaListeners = new Set();
const notifyMeta = () => metaListeners.forEach((l) => l());
function setMeta(patch) {
  meta = { ...meta, ...patch };
  notifyMeta();
}

const signals = new Map(); // handle -> latest record
const signalListeners = new Map(); // handle -> Set<listener>
const activeHandles = new Map(); // handle -> type
const latestByType = new Map(); // sensor type -> latest record
const typeListeners = new Map(); // type -> Set<cb(record)>
const lastCardNotify = new Map(); // handle -> ms of last card re-render
const CARD_REFRESH_MS = 100; // cap live-card React re-renders to ~10 Hz (human-readable; the raw stream is faster)

function onRecord(r) {
  // Store latest + feed graphs/3D at full rate (they consume imperatively, no React re-render).
  signals.set(r.handle, r);
  latestByType.set(r.type, r);
  const tl = typeListeners.get(r.type);
  if (tl) tl.forEach((cb) => cb(r));
  // Throttle the per-card React re-renders: 21 cards re-rendering at stream rate inside a
  // scroll container is what froze scrolling. Cards still show the latest value, at ~10 Hz.
  const now = Date.now();
  if (now - (lastCardNotify.get(r.handle) || 0) >= CARD_REFRESH_MS) {
    lastCardNotify.set(r.handle, now);
    const sl = signalListeners.get(r.handle);
    if (sl) sl.forEach((l) => l());
  }
  if (!activeHandles.has(r.handle)) {
    activeHandles.set(r.handle, r.type);
    setMeta({ active: [...activeHandles.entries()].map(([handle, type]) => ({ handle, type })) });
  }
}

// Imperative access for animation loops (3D / graphs) — no React re-render.
export function getByType(type) {
  return latestByType.get(type);
}
export function subscribeType(type, cb) {
  let set = typeListeners.get(type);
  if (!set) {
    set = new Set();
    typeListeners.set(type, set);
  }
  set.add(cb);
  return () => set.delete(cb);
}

function clearActive() {
  activeHandles.clear();
  latestByType.clear();
  setMeta({ active: [] });
}

// Authoritative active set announced by the phone (live add/remove). Adds newly
// enabled handles (type from the catalog; values fill in as data arrives) and
// drops removed ones immediately — robust for low-rate/on-change sensors that a
// data-gap timeout would wrongly evict.
function applyActiveSet(handles) {
  const wanted = new Set(handles.map(Number));
  const catType = new Map((meta.catalog || []).map((s) => [s.handle, s.type]));
  for (const h of [...activeHandles.keys()]) if (!wanted.has(h)) activeHandles.delete(h);
  for (const h of wanted) if (!activeHandles.has(h)) activeHandles.set(h, catType.get(h) ?? 0);
  const activeTypes = new Set(activeHandles.values());
  for (const ty of [...latestByType.keys()]) if (!activeTypes.has(ty)) latestByType.delete(ty);
  setMeta({ active: [...activeHandles.entries()].map(([handle, type]) => ({ handle, type })) });
}

let ws = null;
let backoff = 500;
let lastDataTs = 0;
let lastHeal = 0;
let everHadDevice = false; // only self-heal a real phone session (not --selftest, which has no device)

// Self-heal the device banner: the server replays a device/catalog snapshot to every
// NEW dashboard connection, but a long-lived socket can miss a phone_connected
// broadcast if the phone drops and reconnects during a socket blip (common when the
// screen locks). If a device was seen, data is clearly still flowing, yet the device
// is now unknown, drop the socket so the fresh connection gets the snapshot again.
setInterval(() => {
  if (ws && ws.readyState === 1 && everHadDevice && !meta.device && Date.now() - lastDataTs < 2000 && Date.now() - lastHeal > 6000) {
    lastHeal = Date.now();
    try {
      ws.close();
    } catch (e) {
      /* ignore */
    }
  }
}, 2000);

function connect() {
  ws = new WebSocket(WS_URL);
  ws.onopen = () => {
    backoff = 500;
    setMeta({ status: "connected" });
  };
  ws.onclose = () => {
    setMeta({ status: "disconnected" });
    clearActive();
    setTimeout(connect, backoff);
    backoff = Math.min(backoff * 2, 5000);
  };
  ws.onerror = () => {
    try {
      ws.close();
    } catch (e) {
      /* ignore */
    }
  };
  ws.onmessage = (ev) => {
    let m;
    try {
      m = JSON.parse(ev.data);
    } catch (e) {
      return;
    }
    switch (m.kind) {
      case "data":
        lastDataTs = Date.now();
        m.records.forEach(onRecord);
        break;
      case "stats":
        // The stats stream also carries the live recording state (rec_rows every
        // ~0.5s); the one-shot "recording" message only fires on toggle.
        setMeta({ stats: m, recording: { active: !!m.recording, rows: m.rec_rows || 0 } });
        break;
      case "debug":
        setMeta({ debug: m });
        break;
      case "recording":
        setMeta({ recording: { active: !!m.active, rows: m.rows || 0 } });
        break;
      case "phone_connected":
        everHadDevice = true;
        setMeta({ catalog: m.sensors || [], device: { model: m.model, android: m.android } });
        break;
      case "active_set":
        applyActiveSet(m.handles || []);
        break;
      case "phone_disconnected":
        setMeta({ catalog: [], device: null });
        clearActive();
        break;
      default:
        break;
    }
  };
}
connect();

export function sendCommand(obj) {
  try {
    if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
  } catch (e) {
    /* ignore */
  }
}

export function useTelemetry() {
  return useSyncExternalStore(
    (cb) => {
      metaListeners.add(cb);
      return () => metaListeners.delete(cb);
    },
    () => meta,
    () => meta
  );
}

export function useSignal(handle) {
  return useSyncExternalStore(
    (cb) => {
      let set = signalListeners.get(handle);
      if (!set) {
        set = new Set();
        signalListeners.set(handle, set);
      }
      set.add(cb);
      return () => set.delete(cb);
    },
    () => signals.get(handle),
    () => signals.get(handle)
  );
}
