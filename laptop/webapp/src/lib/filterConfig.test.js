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
