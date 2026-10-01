import { describe, it, expect } from "vitest";
import { responseDb } from "./filterResponse.js";

describe("filter frequency response (matches sensorstream/filters.py)", () => {
  it("2nd-order Butterworth is -3 dB at the cutoff", () => {
    const [db] = responseDb({ lowpass: { hz: 10, order: 2 }, notches: [] }, 200, [10]);
    expect(db).toBeCloseTo(-3.01, 1);
  });
  it("4th-order low-pass is at least 30 dB down at 10x the cutoff and flat well below it", () => {
    const [low, high] = responseDb({ lowpass: { hz: 5, order: 4 }, notches: [] }, 200, [0.5, 50]);
    expect(low).toBeGreaterThan(-0.1);
    expect(high).toBeLessThan(-30);
  });
  it("a notch is deep at its frequency", () => {
    const [db] = responseDb({ lowpass: null, notches: [{ hz: 8, q: 10 }] }, 100, [8]);
    expect(db).toBeLessThan(-40);
  });
});

import { responseCurve } from "./filterResponse.js";

describe("response curve for plotting", () => {
  it("scales to the spectrum top and floors at -60 dB so the log axis stays readable", () => {
    const ys = responseCurve({ lowpass: { hz: 2, order: 4 }, notches: [] }, 100, [0.5, 45], 1e-2);
    expect(ys[0]).toBeCloseTo(1e-2, 4);
    expect(ys[1]).toBeCloseTo(1e-8, 12); // 1e-2 * 10^(-60/10)
  });
});
