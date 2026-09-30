// Rolling per-sensor history for the dashboard: the last ~60 s of the display-rate stream,
// one fixed-size ring per handle (memory is bounded no matter how long the session runs).
// Feeds every graph (so a newly pinned sensor shows data immediately), the picker's live Hz
// and health dots, and the Snapshot CSV. This is NOT the lossless recording.
// Pure module: no browser or WebSocket dependency, so it unit-tests in node. liveHistory.js
// wires the app's single instance to the telemetry store.

export const CAP = 7200; // 60 s at 120 Hz: above the server's UI cap, so a 60 s window always fits
export const MAX_VALUES = 4;
// On-change sensors legitimately go quiet: light, proximity, significant motion, step detector/counter.
export const ON_CHANGE_TYPES = new Set([5, 8, 17, 18, 19]);
const HZ_LOG_LEN = 30; // one live-Hz sample per second -> 30 s for the health median
const QUIET_MS = 1000;
const RESTART_BACKSTEP_S = 1; // phone clock jumped back this far -> new session, drop old samples

function makeRing(type) {
  return {
    type,
    t: new Float64Array(CAP),
    v: Array.from({ length: MAX_VALUES }, () => new Float64Array(CAP)),
    head: 0, // next write position
    n: 0,
    nv: 0,
    lastSeenMs: 0,
    hz: 0,
    hzLog: [],
    hzLogMs: -Infinity,
  };
}

function reset(r, type) {
  r.type = type;
  r.head = 0;
  r.n = 0;
  r.nv = 0;
  r.hzLog = [];
  r.hzLogMs = -Infinity;
}

const at = (r, i) => (r.head - r.n + i + CAP) % CAP; // storage index of the i-th oldest sample
const newestT = (r) => r.t[at(r, r.n - 1)];

function countedHz(r) {
  if (r.n === 0) return 0;
  const newest = newestT(r);
  let k = 0;
  while (k < r.n && r.t[at(r, r.n - 1 - k)] > newest - 1) k++;
  return k;
}
const liveHzOf = (r) => (r.hz > 0 ? r.hz : countedHz(r));

function median(a) {
  const s = [...a].sort((x, y) => x - y);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function csvField(s) {
  const str = String(s);
  return /[",\r\n]/.test(str) ? `"${str.replace(/"/g, '""')}"` : str;
}
const csvNum = (x) => (Number.isFinite(x) ? String(x) : "");

export function createHistory() {
  const rings = new Map(); // handle -> ring
  const listeners = new Map(); // handle -> Set<cb>

  function push(rec, nowMs) {
    if (!rec || !rec.v) return;
    let r = rings.get(rec.handle);
    if (!r) rings.set(rec.handle, (r = makeRing(rec.type)));
    else if (r.type !== rec.type) reset(r, rec.type);
    r.lastSeenMs = nowMs;
    const t = rec.t / 1e9;
    if (r.n > 0) {
      const newest = newestT(r);
      if (t < newest - RESTART_BACKSTEP_S) reset(r, rec.type);
      else if (t <= newest) return; // late (reordered) or duplicate: keep the series monotonic
    }
    const nv = Math.min(rec.v.length, MAX_VALUES);
    r.t[r.head] = t;
    for (let i = 0; i < MAX_VALUES; i++) r.v[i][r.head] = i < nv && rec.v[i] != null ? rec.v[i] : NaN;
    r.nv = Math.max(r.nv, nv);
    r.head = (r.head + 1) % CAP;
    if (r.n < CAP) r.n++;
    r.hz = rec.hz > 0 ? rec.hz : 0;
    if (nowMs - r.hzLogMs >= 1000) {
      r.hzLogMs = nowMs;
      r.hzLog.push(liveHzOf(r));
      if (r.hzLog.length > HZ_LOG_LEN) r.hzLog.shift();
    }
    const ls = listeners.get(rec.handle);
    if (ls) ls.forEach((cb) => cb());
  }

  function read(handle, seconds) {
    const r = rings.get(handle);
    if (!r || r.n === 0) return { t: new Float64Array(0), v: [] };
    const newest = newestT(r);
    let k = r.n;
    while (k > 0 && r.t[at(r, k - 1)] >= newest - seconds - 1e-9) k--;
    const m = r.n - k;
    const t = new Float64Array(m);
    const v = Array.from({ length: r.nv }, () => new Float64Array(m));
    for (let j = 0; j < m; j++) {
      const p = at(r, k + j);
      t[j] = r.t[p] - newest;
      for (let a = 0; a < r.nv; a++) v[a][j] = r.v[a][p];
    }
    return { t, v };
  }

  function latest(handle) {
    const r = rings.get(handle);
    if (!r || r.n === 0) return null;
    const p = at(r, r.n - 1);
    return { t: r.t[p], v: Array.from({ length: r.nv }, (_, a) => r.v[a][p]) };
  }

  function liveHz(handle) {
    const r = rings.get(handle);
    return r ? liveHzOf(r) : 0;
  }

  function health(handle, nowMs) {
    const r = rings.get(handle);
    if (!r || r.n === 0) return "off";
    if (!ON_CHANGE_TYPES.has(r.type) && nowMs - r.lastSeenMs > QUIET_MS) return "warn";
    if (r.hzLog.length < 3) return "ok";
    const med = median(r.hzLog);
    return med > 0 && liveHzOf(r) < 0.9 * med ? "warn" : "ok";
  }

  function subscribe(handle, cb) {
    let set = listeners.get(handle);
    if (!set) listeners.set(handle, (set = new Set()));
    set.add(cb);
    return () => set.delete(cb);
  }

  const handles = () => [...rings.keys()].filter((h) => rings.get(h).n > 0);

  function snapshotCsv({ handles: hs, seconds, names = new Map(), device = null, exportedIso = new Date().toISOString() }) {
    const lines = [
      "# SensorStream snapshot - display-rate data (<=UI cap per sensor), not the lossless recording",
      `# device=${device?.model ?? "unknown"}, android=${device?.android ?? "unknown"}, exported=${exportedIso}, range_s=${seconds}`,
      "t_s,sensor,handle,x,y,z,w",
    ];
    const used = hs.map((h) => [h, rings.get(h)]).filter(([, r]) => r && r.n > 0);
    if (used.length) {
      const end = Math.max(...used.map(([, r]) => newestT(r)));
      const rows = [];
      for (const [h, r] of used) {
        for (let i = 0; i < r.n; i++) {
          const p = at(r, i);
          if (r.t[p] >= end - seconds - 1e-9) rows.push({ t: r.t[p], h, r, p });
        }
      }
      rows.sort((a, b) => a.t - b.t || a.h - b.h);
      const t0 = rows.length ? rows[0].t : 0;
      for (const { t, h, r, p } of rows) {
        const vals = [0, 1, 2, 3].map((a) => (a < r.nv ? csvNum(r.v[a][p]) : ""));
        lines.push(`${(t - t0).toFixed(6)},${csvField(names.get(h) ?? `handle ${h}`)},${h},${vals.join(",")}`);
      }
    }
    return lines.join("\n") + "\n";
  }

  return { push, read, latest, liveHz, health, subscribe, handles, snapshotCsv };
}
