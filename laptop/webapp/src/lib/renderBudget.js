// Shared render-budget signal (ADR-001 follow-up: the "visualization scheduler", kept light).
//
// One source of truth for scroll state + per-visualization frame rate, so the 3D view and
// the live plots DEGRADE (never freeze) while the user scrolls, and auto-recover when idle.
// No React: consumers read this imperatively inside their existing rAF loops.
//
// Contract (acceptance): during scroll the 3D and plots keep updating at a reduced rate
// (never 0 while visible); when idle they return to normal; off-screen viz is paused
// separately by each component's IntersectionObserver.

const IDLE_MS = 150; // "scrolling" persists this long after the last scroll event

// Per-visualization frame targets — the ONE place to tune. Values are the minimum ms
// between renders (lower = faster). Start here; adjust from foreground DevTools traces.
const TARGETS = {
  "3d": { normal: 1000 / 30, scroll: 1000 / 15 }, // 30 fps -> 15 fps while scrolling
  plot: { normal: 1000 / 30, scroll: 1000 / 10 }, // 30 Hz  -> 10 Hz while scrolling
};

let lastScrollTs = -Infinity;
let installed = false;

function onScroll() {
  lastScrollTs = performance.now(); // cheapest possible handler: a single write (no timer, no React)
}

function ensureInstalled() {
  if (installed || typeof document === "undefined") return;
  installed = true;
  // Capture phase catches scrolling from any container; passive keeps the handler off the
  // critical path so it never delays the browser's own scroll handling.
  document.addEventListener("scroll", onScroll, { capture: true, passive: true });
}

export function isScrolling() {
  ensureInstalled();
  return performance.now() - lastScrollTs < IDLE_MS;
}

/** Minimum ms between renders for a consumer ('3d' | 'plot') given the current scroll mode. */
export function frameIntervalMs(kind) {
  const t = TARGETS[kind] || TARGETS["3d"];
  return isScrolling() ? t.scroll : t.normal;
}

// --- lightweight instrumentation for the dev overlay (zero cost unless stats() is read) ---
const counters = {}; // kind -> { count, windowStart, rate, last }

/** Consumers call this right after they actually render a frame. */
export function report(kind) {
  const now = performance.now();
  let c = counters[kind];
  if (!c) c = counters[kind] = { count: 0, windowStart: now, rate: 0, last: now };
  c.count += 1;
  c.last = now;
  const dt = now - c.windowStart;
  if (dt >= 1000) {
    c.rate = Math.round((c.count * 1000) / dt);
    c.count = 0;
    c.windowStart = now;
  }
}

/** Effective rates for the perf overlay; a consumer idle >1.5 s reads as 0 (not a stale value). */
export function stats() {
  const now = performance.now();
  const rateOf = (k) => {
    const c = counters[k];
    if (!c) return 0;
    return now - c.last > 1500 ? 0 : c.rate;
  };
  return { scrolling: isScrolling(), fps3d: rateOf("3d"), hzPlot: rateOf("plot") };
}
