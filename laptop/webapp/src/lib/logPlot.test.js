import { describe, it, expect } from "vitest";
import { clampDecades } from "./logPlot.js";

describe("clampDecades (log-axis plots)", () => {
  it("lifts values more than N decades below the top to the floor, keeps nulls", () => {
    const { series, min, max } = clampDecades([[1e-2, 1e-12, null], [1e-21, 5e-3, 1e-5]], 8);
    expect(max).toBe(1e-2);
    expect(min).toBeCloseTo(1e-10, 20);
    expect(series[0]).toEqual([1e-2, 1e-10, null]);
    expect(series[1][0]).toBeCloseTo(1e-10, 20);
    expect(series[1][1]).toBe(5e-3);
  });
  it("handles all-null input", () => {
    expect(clampDecades([[null, null]], 8).max).toBeNull();
  });
});
