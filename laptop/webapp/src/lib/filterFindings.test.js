import { describe, it, expect } from "vitest";
import { findingsRows } from "./filterFindings.js";

const axis = (o) => ({ noise_density: 0.0012, cutoff_hz: 4.8, order: 4, delay_ms: 86.6, notches: [], sigma_raw: 0.014, sigma_filtered: 0.0031, ...o });

describe("findings rows", () => {
  it("formats each axis", () => {
    const rows = findingsRows({ axes: [axis(), axis({ order: 2 }), axis({ notches: [{ hz: 8, q: 10, prominence: 35.2 }, { hz: 12.5, q: 10, prominence: 11 }] })] });
    expect(rows.map((r) => r.axis)).toEqual(["X", "Y", "Z"]);
    expect(rows[0]).toEqual({ axis: "X", noise: "1.2e-3", lowpass: "4.8 Hz · 4th", delay: "87 ms", notches: "–", sigma: "0.0140 → 0.00310", gain: "4.5×" });
    expect(rows[1].lowpass).toBe("4.8 Hz · 2nd");
    expect(rows[2].notches).toBe("8 Hz ×35, 12.5 Hz ×11");
  });

  it("copes with missing parts", () => {
    expect(findingsRows(null)).toEqual([]);
    expect(findingsRows({})).toEqual([]);
    const [r] = findingsRows({ axes: [axis({ noise_density: null, cutoff_hz: null, order: null, delay_ms: 0, sigma_filtered: 0 })] });
    expect(r).toMatchObject({ noise: "–", lowpass: "–", delay: "–", gain: "–" });
  });
});
