import { describe, it, expect } from "vitest";
import { withGaps } from "./seriesSpec.js";

describe("withGaps", () => {
  it("inserts a null break where samples are missing, so the plot does not bridge an outage", () => {
    const t = Float64Array.from([-12, -11.9, -11.8, -0.2, -0.1, 0]);
    const y = Float64Array.from([1, 2, 3, 4, 5, 6]);
    const [tt, yy] = withGaps([t, y], 0.5);
    expect(Array.from(tt)).toEqual([-12, -11.9, -11.8, -11.8, -0.2, -0.1, 0]);
    expect(Array.from(yy)).toEqual([1, 2, 3, null, 4, 5, 6]);
  });

  it("returns the input untouched when there is no gap", () => {
    const t = Float64Array.from([-0.2, -0.1, 0]);
    const y = Float64Array.from([1, 2, 3]);
    const out = withGaps([t, y], 0.5);
    expect(out[0]).toBe(t);
    expect(out[1]).toBe(y);
  });
});
