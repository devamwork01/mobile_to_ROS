// Filter configuration helpers shared by the editor. Mirrors the server's validation
// (sensorstream/filters.py) so the editor can explain problems before Apply.
const EXCLUDED = new Set([11, 15, 20, 5, 8, 17, 18, 19]);
export const MAX_NOTCHES = 3;

export const FILTERABLE = (type, kind) => !EXCLUDED.has(type) && (kind === "vector" || kind === "scalar");

const num = (x) => (typeof x === "number" && Number.isFinite(x) ? x : null);

export function validateConfig(cfg, fs) {
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

export const emptyDraft = () => ({ lpOn: true, lpHz: 10, order: 4, notches: [] });

export function draftToConfig(d) {
  return {
    lowpass: d.lpOn ? { hz: Number(d.lpHz), order: Number(d.order) } : null,
    notches: d.notches.map((n) => ({ hz: Number(n.hz), q: Number(n.q) })),
  };
}

export function configToDraft(cfg) {
  if (!cfg) return emptyDraft();
  return {
    lpOn: !!cfg.lowpass,
    lpHz: cfg.lowpass ? cfg.lowpass.hz : 10,
    order: cfg.lowpass ? cfg.lowpass.order : 4,
    notches: (cfg.notches || []).map((n) => ({ hz: n.hz, q: n.q })),
  };
}
