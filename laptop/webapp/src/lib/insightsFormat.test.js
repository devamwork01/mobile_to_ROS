import { describe, it, expect } from "vitest";
import { fmtSig, reportToCsv } from "./insightsFormat.js";

describe("fmtSig", () => {
  it("uses fixed notation for ordinary values and exponent for tiny/huge ones", () => {
    expect(fmtSig(9.8123)).toBe("9.81");
    expect(fmtSig(0.01234)).toBe("0.0123");
    expect(fmtSig(0.0000612)).toBe("6.12e-5");
    expect(fmtSig(null)).toBe("—");
    expect(fmtSig(0)).toBe("0");
  });
});

describe("reportToCsv", () => {
  it("writes one row per sensor x axis (+ magnitude) with Allan points", () => {
    const rep = {
      id: "s1", preset: "still",
      sensors: [{
        name: "Gyro, main", handle: 4, type: 4, unit: "rad/s",
        rate: { rate_hz: 100, jitter_ms: 0.4, gaps: 0, lost: 1 },
        axes: { x: { mean: 0.001, bias: 0.001, std: 0.01, p2p: 0.05, noise_density: 1e-3, drift_per_min: 0 } },
        adev_points: { x: { random_walk: 0.001, bias_instability: 0.0002, bi_tau: 20 } },
      }],
    };
    const lines = reportToCsv(rep).trim().split("\n");
    expect(lines[0]).toBe("sensor,handle,axis,unit,mean,bias,std,p2p,noise_density,drift_per_min,rate_hz,jitter_ms,gaps,lost,random_walk,bias_instability,bi_tau_s");
    expect(lines[1]).toBe('"Gyro, main",4,x,rad/s,0.001,0.001,0.01,0.05,0.001,0,100,0.4,0,1,0.001,0.0002,20');
  });
});

import { logTick, expTick } from "./insightsFormat.js";

describe("log-axis tick labels", () => {
  it("leave uPlot's unlabeled (null) log ticks blank instead of throwing", () => {
    expect(logTick(null, "Hz")).toBe("");
    expect(expTick(null)).toBe("");
  });
  it("format labeled ticks", () => {
    expect(logTick(0.5, "Hz")).toBe("0.5Hz");
    expect(logTick(20, "Hz")).toBe("20Hz");
    expect(logTick(0.01, "")).toBe("0.01");
    expect(expTick(0.0001)).toBe("1e-4");
    expect(expTick(3)).toBe("3e+0");
  });
});
