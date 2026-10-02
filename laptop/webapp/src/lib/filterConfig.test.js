import { describe, it, expect } from "vitest";
import { validateConfig, emptyDraft, draftToConfig, configToDraft, FILTERABLE } from "./filterConfig.js";

describe("filter config", () => {
  it("mirrors the server's validation", () => {
    expect(validateConfig({ lowpass: { hz: 10, order: 4 }, notches: [] }, 100).ok).toBe(true);
    expect(validateConfig({ lowpass: { hz: 46, order: 4 }, notches: [] }, 100).ok).toBe(false);
    expect(validateConfig({ lowpass: null, notches: [] }, 100).ok).toBe(false);
    expect(validateConfig({ lowpass: null, notches: [{ hz: 5, q: 0.5 }] }, 100).ok).toBe(false);
    expect(validateConfig({ lowpass: null, notches: Array(4).fill({ hz: 5, q: 10 }) }, 100).ok).toBe(false);
    expect(validateConfig({ lowpass: { hz: 10, order: 4 }, notches: [] }, null).ok).toBe(true); // rate unknown: shape only
  });
  it("round-trips drafts", () => {
    const cfg = { lowpass: { hz: 7.5, order: 2 }, notches: [{ hz: 8, q: 10 }] };
    expect(draftToConfig(configToDraft(cfg))).toEqual(cfg);
    const d = emptyDraft();
    expect(draftToConfig({ ...d, lpOn: false, notches: [{ hz: 3, q: 5 }] })).toEqual({ lowpass: null, notches: [{ hz: 3, q: 5 }] });
  });
  it("knows which sensors can be filtered", () => {
    expect(FILTERABLE(1, "vector")).toBe(true);
    expect(FILTERABLE(11, "orientation")).toBe(false);
    expect(FILTERABLE(5, "scalar")).toBe(false);
  });
});

import { filterBadge } from "./filterConfig.js";

describe("filter badge on the panel", () => {
  it("only says 'filtered' when there is a config and no error", () => {
    expect(filterBadge(null, undefined)).toBeNull();
    expect(filterBadge({ lowpass: { hz: 5, order: 4 }, notches: [] }, undefined)).toMatchObject({ label: "filtered", tone: "accent" });
    const b = filterBadge({ lowpass: { hz: 40, order: 4 }, notches: [] }, "Cutoff must be between 0.1 and 22.5 Hz.");
    expect(b).toMatchObject({ label: "filter inactive", tone: "warn", title: "Cutoff must be between 0.1 and 22.5 Hz." });
    expect(filterBadge(null, "This sensor can't be filtered.")).toMatchObject({ label: "filter refused", tone: "warn" });
    // a refused *change* leaves the previous filter running
    expect(filterBadge({ lowpass: { hz: 5, order: 4 }, notches: [] }, "Invalid low-pass settings.", true)).toMatchObject({ label: "filtered", tone: "warn" });
  });
});

import { setPerAxis, filterSummary } from "./filterConfig.js";

describe("per-axis filters", () => {
  const X = { lowpass: { hz: 0.5, order: 4 }, notches: [] };
  const Z = { lowpass: { hz: 5, order: 4 }, notches: [{ hz: 8, q: 10 }] };
  const PER = { ...X, axes: [X, X, Z] };

  it("round-trip and toggle (off keeps X, on copies to all)", () => {
    const d = configToDraft(PER);
    expect(d.perAxis).toBe(true);
    expect(draftToConfig(d)).toEqual(PER);
    const off = setPerAxis(d, false);
    expect(draftToConfig(off)).toEqual(X);
    const on = setPerAxis(off, true);
    expect(draftToConfig(on)).toEqual({ ...X, axes: [X, X, X] });
    expect(configToDraft(X).perAxis).toBe(false);
  });

  it("names the failing axis", () => {
    const bad = { axes: [X, X, { lowpass: { hz: 40, order: 4 }, notches: [] }] };
    const r = validateConfig(bad, 50);
    expect(r.ok).toBe(false);
    expect(r.message).toMatch(/^Z: /);
    expect(validateConfig(PER, 100).ok).toBe(true);
    expect(validateConfig({ axes: [X, X] }, 100).ok).toBe(false);
  });

  it("summarises, grouping identical axes", () => {
    expect(filterSummary(PER)).toBe("X/Y LP 0.5 · Z LP 5 + notch 8");
    expect(filterSummary(X)).toBe("LP 0.5");
    expect(filterSummary(null)).toBe("");
  });
});

import { applySuggestion } from "./filterConfig.js";

describe("applying a suggestion in the editor", () => {
  const S = { lowpass: { hz: 6, order: 4 }, notches: [] };
  const SZ = { lowpass: { hz: 20, order: 4 }, notches: [{ hz: 8, q: 10 }] };
  it("per axis on + per-axis suggestion fills each tab", () => {
    const d = setPerAxis(emptyDraft(), true);
    expect(draftToConfig(applySuggestion(d, S, [S, S, SZ])).axes[2]).toEqual(SZ);
  });
  it("per axis on without a per-axis suggestion stays per axis, all tabs get the combined one", () => {
    // Review: it used to switch per-axis off silently.
    const d = setPerAxis(emptyDraft(), true);
    const out = applySuggestion(d, S, null);
    expect(out.perAxis).toBe(true);
    expect(draftToConfig(out).axes).toEqual([S, S, S]);
  });
  it("per axis off uses the combined suggestion", () => {
    expect(draftToConfig(applySuggestion(emptyDraft(), S, [S, S, SZ]))).toEqual(S);
  });
});

describe("per-axis summary only groups truly identical filters", () => {
  it("different orders are not grouped and the 2nd order is shown", () => {
    const o2 = { lowpass: { hz: 5, order: 2 }, notches: [] }, o4 = { lowpass: { hz: 5, order: 4 }, notches: [] };
    expect(filterSummary({ axes: [o2, o4, o4] })).toBe("X LP 5 (2nd) · Y/Z LP 5");
  });
});

import { followServer } from "./filterConfig.js";

describe("pane draft vs the server's running filter", () => {
  // review I2: the pane stays open, and --filter / another dashboard change the filter under it
  const tuned = { lowpass: { hz: 0.5, order: 4 }, notches: [], axes: [
    { lowpass: { hz: 0.5, order: 4 }, notches: [] }, { lowpass: { hz: 0.5, order: 4 }, notches: [] },
    { lowpass: { hz: 5, order: 4 }, notches: [{ hz: 8, q: 10 }] }] };
  it("an untouched draft follows the server", () => {
    const r = followServer(configToDraft(undefined), tuned, false);
    expect(r.stale).toBe(false);
    expect(draftToConfig(r.draft)).toEqual(draftToConfig(configToDraft(tuned)));
    expect(r.draft.perAxis).toBe(true);
  });
  it("an edited draft is kept and flagged stale", () => {
    const mine = { ...configToDraft(undefined), lpOn: true, lpHz: 3 };
    const r = followServer(mine, tuned, true);
    expect(r.draft).toBe(mine);
    expect(r.stale).toBe(true);
  });
  it("a cleared filter empties an untouched draft", () => {
    expect(followServer(configToDraft(tuned), undefined, false).draft).toEqual(configToDraft(undefined));
  });
});
