import { describe, it, expect } from "vitest";
import { snapshotFilename } from "./snapshot.js";

describe("snapshotFilename", () => {
  it("uses a filesystem-safe model and local timestamp", () => {
    const d = new Date(2026, 9, 1, 9, 5, 7); // 2026-10-01 09:05:07 local
    expect(snapshotFilename("Galaxy S25 Ultra", d)).toBe("sensorstream_Galaxy-S25-Ultra_20261001-090507.csv");
    expect(snapshotFilename(null, d)).toBe("sensorstream_device_20261001-090507.csv");
    expect(snapshotFilename("///", d)).toBe("sensorstream_device_20261001-090507.csv");
  });
});
