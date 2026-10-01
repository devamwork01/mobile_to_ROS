import { describe, it, expect } from "vitest";
import { seriesSpec, withGaps, yRange, minSpanFor } from "./seriesSpec.js";

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

describe("scalar sensors", () => {
  it("scalar kind is one series without magnitude", () => {
    expect(seriesSpec("scalar")).toEqual({ labels: ["value"], n: 1, mag: false });
  });

  it("quantization noise does not fill the plot: span widened to the sensor's minimum", () => {
    // barometer at 913 hPa with 0.02 hPa of noise -> at least 1 hPa visible, centred
    const [lo, hi] = yRange(913.27, 913.29, minSpanFor("hPa"));
    expect(hi - lo).toBeCloseTo(1, 6);
    expect((lo + hi) / 2).toBeCloseTo(913.28, 6);
  });

  it("a real change keeps its full span (plus a margin)", () => {
    const [lo, hi] = yRange(50, 450, minSpanFor("lx"));
    expect(lo).toBeLessThan(50);
    expect(hi).toBeGreaterThan(450);
    expect(hi - lo).toBeLessThan(450);
  });

  it("no data gives a harmless default range", () => {
    expect(yRange(null, null, 1)).toEqual([0, 1]);
    expect(minSpanFor("m/s²")).toBe(0);
  });
});
