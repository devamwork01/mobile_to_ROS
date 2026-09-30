import { describe, it, expect } from "vitest";
import { createHistory, CAP } from "./history.js";

const NS = 1e9;
const rec = (handle, tSec, v, extra = {}) => ({ handle, type: 1, t: tSec * NS, v, ...extra });

describe("history ring", () => {
  it("reads a window relative to the newest sample", () => {
    const h = createHistory();
    for (let i = 0; i <= 100; i++) h.push(rec(7, i * 0.1, [i, -i, 0]), 0);
    const { t, v } = h.read(7, 2);
    expect(t.length).toBe(21); // 8.0 .. 10.0 inclusive
    expect(t[t.length - 1]).toBe(0);
    expect(t[0]).toBeCloseTo(-2, 6);
    expect(v.length).toBe(3);
    expect(v[0][v[0].length - 1]).toBe(100);
    expect(v[1][0]).toBe(-80);
  });

  it("is bounded: overwrites the oldest after CAP samples", () => {
    const h = createHistory();
    for (let i = 0; i < CAP + 500; i++) h.push(rec(1, i * 0.001, [i]), 0);
    const { t, v } = h.read(1, 1e9);
    expect(t.length).toBe(CAP);
    expect(v[0][0]).toBe(500);
  });

  it("drops late and duplicate samples", () => {
    const h = createHistory();
    h.push(rec(1, 1.0, [1]), 0);
    h.push(rec(1, 1.2, [2]), 0);
    h.push(rec(1, 1.1, [9]), 0); // reordered
    h.push(rec(1, 1.2, [9]), 0); // duplicate time
    expect(Array.from(h.read(1, 60).v[0])).toEqual([1, 2]);
  });

  it("resets when the phone clock jumps back", () => {
    const h = createHistory();
    h.push(rec(1, 500, [1]), 0);
    h.push(rec(1, 501, [2]), 0);
    h.push(rec(1, 3, [3]), 0);
    expect(Array.from(h.read(1, 60).v[0])).toEqual([3]);
  });

  it("resets when a handle changes type", () => {
    const h = createHistory();
    h.push(rec(1, 1, [1, 2, 3]), 0);
    h.push({ handle: 1, type: 4, t: 2 * NS, v: [5, 6, 7] }, 0);
    expect(Array.from(h.read(1, 60).v[0])).toEqual([5]);
  });

  it("keeps at most 4 values and stores null as NaN", () => {
    const h = createHistory();
    h.push(rec(1, 1, [1, 2, 3, 4, 5, 6]), 0);
    h.push(rec(2, 1, [null, 2, 3]), 0);
    expect(h.read(1, 60).v.length).toBe(4);
    expect(Number.isNaN(h.read(2, 60).v[0][0])).toBe(true);
  });

  it("latest returns the newest sample", () => {
    const h = createHistory();
    expect(h.latest(3)).toBeNull();
    h.push(rec(3, 1, [1, 2, 3]), 0);
    h.push(rec(3, 2, [4, 5, 6]), 0);
    expect(h.latest(3)).toEqual({ t: 2, v: [4, 5, 6] });
  });

  it("notifies subscribers and stops after unsubscribe", () => {
    const h = createHistory();
    let n = 0;
    const off = h.subscribe(5, () => n++);
    h.push(rec(5, 1, [1]), 0);
    off();
    h.push(rec(5, 2, [1]), 0);
    expect(n).toBe(1);
  });
});

describe("history rate and health", () => {
  it("uses the server hz when present, else counts the last second", () => {
    const h = createHistory();
    h.push(rec(1, 1, [1], { hz: 99.5 }), 0);
    expect(h.liveHz(1)).toBe(99.5);
    const g = createHistory();
    for (let i = 0; i < 50; i++) g.push(rec(2, 10 + i * 0.04, [i]), 0); // 25 Hz over 2 s
    expect([24, 25, 26]).toContain(g.liveHz(2));
  });

  it("is off for unknown handles, warn when a continuous sensor goes quiet", () => {
    const h = createHistory();
    expect(h.health(1, 0)).toBe("off");
    h.push(rec(1, 1, [1], { hz: 100 }), 1000);
    expect(h.health(1, 1500)).toBe("ok");
    expect(h.health(1, 2500)).toBe("warn");
  });

  it("does not flag on-change sensors (light) as quiet", () => {
    const h = createHistory();
    h.push({ handle: 9, type: 5, t: NS, v: [300], hz: 1 }, 1000);
    expect(h.health(9, 60000)).toBe("ok");
  });

  it("warns when the rate drops below 90% of its median", () => {
    const h = createHistory();
    let t = 0;
    for (let s = 0; s < 10; s++) h.push(rec(1, (t += 1), [1], { hz: 100 }), s * 1000);
    expect(h.health(1, 9000)).toBe("ok");
    h.push(rec(1, (t += 1), [1], { hz: 60 }), 10000);
    expect(h.health(1, 10000)).toBe("warn");
  });
});

describe("snapshotCsv", () => {
  it("writes header, sorts rows by time across sensors, times relative to first row", () => {
    const h = createHistory();
    h.push(rec(1, 100.0, [1, 2, 3]), 0);
    h.push(rec(2, 100.05, [7]), 0);
    h.push(rec(1, 100.1, [4, 5, 6]), 0);
    const csv = h.snapshotCsv({
      handles: [1, 2],
      seconds: 60,
      names: new Map([[1, "Acceleration"], [2, "Ambient Light"]]),
      device: { model: "Pixel 8", android: "15" },
      exportedIso: "2026-10-01T10:00:00.000Z",
    });
    expect(csv.split("\n")).toEqual([
      "# SensorStream snapshot - display-rate data (<=UI cap per sensor), not the lossless recording",
      "# device=Pixel 8, android=15, exported=2026-10-01T10:00:00.000Z, range_s=60",
      "t_s,sensor,handle,x,y,z,w",
      "0.000000,Acceleration,1,1,2,3,",
      "0.050000,Ambient Light,2,7,,,",
      "0.100000,Acceleration,1,4,5,6,",
      "",
    ]);
  });

  it("limits to the last N seconds of the newest exported sample", () => {
    const h = createHistory();
    for (let i = 0; i <= 20; i++) h.push(rec(1, i, [i]), 0);
    const rows = h.snapshotCsv({ handles: [1], seconds: 5 }).trim().split("\n").slice(3);
    expect(rows.length).toBe(6); // t = 15..20
    expect(rows[0]).toBe("0.000000,handle 1,1,15,,,");
  });

  it("quotes names with commas and quotes; unknown device", () => {
    const h = createHistory();
    h.push(rec(1, 1, [1]), 0);
    const csv = h.snapshotCsv({ handles: [1], seconds: 60, names: new Map([[1, 'BMI "260", Accel']]) });
    expect(csv).toContain("device=unknown, android=unknown");
    expect(csv).toContain('0.000000,"BMI ""260"", Accel",1,1,,,');
  });

  it("returns only headers when there is no data", () => {
    const h = createHistory();
    expect(h.snapshotCsv({ handles: [4], seconds: 10 }).trim().split("\n").length).toBe(3);
  });
});

describe("review fixes", () => {
  it("read(..., {until}) returns the window ending at a frozen time (paused graph)", () => {
    const h = createHistory();
    for (let i = 0; i <= 20; i++) h.push(rec(1, i, [i]), 0);
    const { t, v } = h.read(1, 5, { until: 10 });
    expect(Array.from(v[0])).toEqual([5, 6, 7, 8, 9, 10]);
    expect(t[t.length - 1]).toBe(0);
  });

  it("read(..., {into}) fills reusable scratch buffers instead of allocating", () => {
    const h = createHistory();
    for (let i = 0; i < 10; i++) h.push(rec(1, i, [i, 2 * i, 3 * i]), 0);
    const into = h.scratch();
    const a = h.read(1, 60, { into });
    expect(a.t.buffer).toBe(into.t.buffer);
    expect(a.v[1].buffer).toBe(into.v[1].buffer);
    expect(Array.from(a.v[2])).toEqual([0, 3, 6, 9, 12, 15, 18, 21, 24, 27]);
  });

  it("snapshotCsv writes only each sensor's own value count (no bias values under w)", () => {
    const h = createHistory();
    h.push(rec(1, 1, [1, 2, 3, 0.1, 0.2, 0.3]), 0); // uncalibrated: xyz + bias
    const csv = h.snapshotCsv({ handles: [1], seconds: 60, valueCounts: new Map([[1, 3]]) });
    expect(csv.trim().split("\n")[3]).toBe("0.000000,handle 1,1,1,2,3,");
  });
});
