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
