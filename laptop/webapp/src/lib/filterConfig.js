// Filter configuration helpers shared by the editor. Mirrors the server's validation
// (sensorstream/filters.py) so the editor can explain problems before Apply.
const EXCLUDED = new Set([11, 15, 20, 5, 8, 17, 18, 19]);
export const MAX_NOTCHES = 3;

export const FILTERABLE = (type, kind) => !EXCLUDED.has(type) && (kind === "vector" || kind === "scalar");

const num = (x) => (typeof x === "number" && Number.isFinite(x) ? x : null);

export const AXIS_NAMES = ["X", "Y", "Z"];

// Same rules as the server (filters.validate): a per-axis config is refused as a whole when any
// axis is invalid, with that axis named.
export function validateConfig(cfg, fs) {
  if (cfg?.axes) {
    if (!Array.isArray(cfg.axes) || cfg.axes.length !== 3) return { ok: false, message: "Per-axis filters need X, Y and Z settings." };
    for (let i = 0; i < 3; i++) {
      const r = validateOne(cfg.axes[i], fs);
      if (!r.ok) return { ok: false, message: `${AXIS_NAMES[i]}: ${r.message}` };
    }
    return { ok: true, message: "" };
  }
  return validateOne(cfg, fs);
}

function validateOne(cfg, fs) {
  const nyq = fs ? 0.45 * fs : Infinity;
  const lp = cfg?.lowpass;
  const notches = cfg?.notches || [];
  if (!lp && notches.length === 0) return { ok: false, message: "Enable the low-pass or add a notch." };
  if (lp) {
    const hz = num(lp.hz);
    if (hz == null || ![2, 4].includes(lp.order)) return { ok: false, message: "Low-pass needs a cutoff and order 2 or 4." };
    if (hz < 0.1 || hz > nyq) return { ok: false, message: `Cutoff must be between 0.1 and ${nyq.toFixed(1)} Hz.` };
  }
  if (notches.length > MAX_NOTCHES) return { ok: false, message: `At most ${MAX_NOTCHES} notches.` };
  for (const n of notches) {
    const hz = num(n?.hz), q = num(n?.q);
    if (hz == null || q == null) return { ok: false, message: "Each notch needs a frequency and Q." };
    if (hz < 0.2 || hz > nyq) return { ok: false, message: `Notch must be between 0.2 and ${nyq.toFixed(1)} Hz.` };
    if (q < 1 || q > 50) return { ok: false, message: "Notch Q must be between 1 and 50." };
  }
  return { ok: true, message: "" };
}

export const emptyDraft = () => ({ lpOn: true, lpHz: 10, order: 4, notches: [], perAxis: false, axes: null });

const plainDraft = (cfg) => ({
  lpOn: !!cfg?.lowpass,
  lpHz: cfg?.lowpass ? cfg.lowpass.hz : 10,
  order: cfg?.lowpass ? cfg.lowpass.order : 4,
  notches: (cfg?.notches || []).map((n) => ({ hz: n.hz, q: n.q })),
});

const plainConfig = (d) => ({
  lowpass: d.lpOn ? { hz: Number(d.lpHz), order: Number(d.order) } : null,
  notches: d.notches.map((n) => ({ hz: Number(n.hz), q: Number(n.q) })),
});

// Draft = the editor's state: the all-axes fields, plus perAxis + three per-axis drafts.
// A per-axis config mirrors X at the top level (as the server stores it).
export function draftToConfig(d) {
  if (d.perAxis && d.axes) {
    const axes = d.axes.map(plainConfig);
    return { ...axes[0], axes };
  }
  return plainConfig(d);
}

export function configToDraft(cfg) {
  if (!cfg) return emptyDraft();
  return { ...plainDraft(cfg), perAxis: !!cfg.axes, axes: cfg.axes ? cfg.axes.map(plainDraft) : null };
}

// Switching on copies the current filter to X, Y and Z; switching off keeps X's filter for all.
export function setPerAxis(d, on) {
  const copy = (x) => ({ ...x, notches: x.notches.map((n) => ({ ...n })) });
  if (on) {
    const top = { lpOn: d.lpOn, lpHz: d.lpHz, order: d.order, notches: d.notches };
    return { ...d, perAxis: true, axes: [copy(top), copy(top), copy(top)] };
  }
  const x = d.axes ? d.axes[0] : d;
  return { ...d, ...copy(x), perAxis: false, axes: null };
}

// A server suggestion applied to the editor draft: per axis on -> each tab from its own axis's
// suggestion, or (no per-axis suggestion) the combined one in every tab; off -> the combined one.
export function applySuggestion(d, combined, axes) {
  if (d.perAxis) {
    const per = axes && axes.length === 3 ? axes : [combined, combined, combined];
    return configToDraft({ ...per[0], axes: per });
  }
  return { ...configToDraft(combined), perAxis: false, axes: null };
}

const summaryOne = (cfg) =>
  [cfg?.lowpass ? `LP ${+cfg.lowpass.hz}${cfg.lowpass.order === 2 ? " (2nd)" : ""}` : null, (cfg?.notches || []).length ? `notch ${cfg.notches.map((n) => +n.hz).join(", ")}` : null]
    .filter(Boolean)
    .join(" + ");

// e.g. "LP 5 + notch 8", or per axis "X/Y LP 0.5 · Z LP 5 + notch 8" (identical axes grouped).
export function filterSummary(cfg) {
  if (!cfg) return "";
  if (!cfg.axes) return summaryOne(cfg);
  // Group axes only when their filters are identical (not merely displayed alike).
  const groups = new Map();
  cfg.axes.forEach((ax, i) => {
    const k = JSON.stringify(plainConfig(plainDraft(ax)));
    const g = groups.get(k) || { text: summaryOne(ax), names: [] };
    g.names.push(AXIS_NAMES[i]);
    groups.set(k, g);
  });
  return [...groups.values()].map((g) => `${g.names.join("/")} ${g.text}`).join(" · ");
}

// What the panel header says about its filter. The server can refuse a config, or be unable to
// run it at the current sample rate - the panel must not claim "filtered" then.
export function filterBadge(config, error, running = false) {
  if (error && running) return { label: "filtered", tone: "warn", title: `Last change refused: ${error}` };
  if (error) return { label: config ? "filter inactive" : "filter refused", tone: "warn", title: error };
  if (config) return { label: "filtered", tone: "accent", title: "A filter is running on this sensor (server-side, full rate)" };
  return null;
}

// The pane stays open while the server's running filter can change under it (--filter tuning,
// a reconnect re-tune, another dashboard). An untouched draft follows the server; an edited one is
// kept and flagged stale, so Apply never silently replaces a newer filter.
export function followServer(draft, active, dirty) {
  return dirty ? { draft, stale: true } : { draft: configToDraft(active), stale: false };
}
