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

function onRecord(r) {
  signals.set(r.handle, r);
  const s = signalListeners.get(r.handle);
  if (s) s.forEach((l) => l());
  if (!activeHandles.has(r.handle)) {
    activeHandles.set(r.handle, r.type);
    setMeta({ active: [...activeHandles.entries()].map(([handle, type]) => ({ handle, type })) });
  }
}

function clearActive() {
  activeHandles.clear();
  setMeta({ active: [] });
}

let ws = null;
let backoff = 500;
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
        m.records.forEach(onRecord);
        break;
      case "stats":
        setMeta({ stats: m });
        break;
      case "debug":
        setMeta({ debug: m });
        break;
      case "recording":
        setMeta({ recording: { active: !!m.active, rows: m.rows || 0 } });
        break;
      case "phone_connected":
        setMeta({ catalog: m.sensors || [], device: { model: m.model, android: m.android } });
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
