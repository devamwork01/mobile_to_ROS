import { describe, it, expect } from "vitest";
import { listSensors, resolveKey, defaultPinKeys, sensorKey, keyLabel, pinKeyFor, PIN_3D } from "./sensors.js";

const catalog = [
  { handle: 3, type: 4, name: "LSM6DSO Gyroscope", stringType: "android.sensor.gyroscope" },
  { handle: 1, type: 1, name: "LSM6DSO Accelerometer", stringType: "android.sensor.accelerometer" },
  { handle: 8, type: 5, name: "TCS3701 Light", stringType: "android.sensor.light" },
  { handle: 30, type: 65601, name: "Vendor Thing", stringType: "com.vendor.sensor.super_thing" },
];

describe("listSensors", () => {
  it("builds rows from the catalog with display labels and active flags", () => {
    const rows = listSensors(catalog, [{ handle: 1, type: 1 }, { handle: 3, type: 4 }]);
    expect(rows.map((r) => r.label)).toEqual(["Acceleration", "Ambient Light", "Angular Velocity", "Super Thing"]);
    const acc = rows.find((r) => r.handle === 1);
    expect(acc).toMatchObject({ key: "1:LSM6DSO Accelerometer", kind: "vector", unit: "m/s²", active: true, detail: "LSM6DSO Accelerometer" });
    expect(rows.find((r) => r.handle === 8).active).toBe(false);
  });

  it("adds active handles missing from the catalog (selftest)", () => {
    const rows = listSensors([], [{ handle: 2, type: 11 }]);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ handle: 2, key: "11:Orientation", kind: "orientation", active: true });
  });
});

describe("resolveKey", () => {
  it("re-attaches a pin after the phone reconnects with new handles", () => {
    const before = listSensors(catalog, [{ handle: 1, type: 1 }]);
    const key = before.find((r) => r.handle === 1).key;
    const after = listSensors([{ handle: 41, type: 1, name: "LSM6DSO Accelerometer" }], [{ handle: 41, type: 1 }]);
    expect(resolveKey(key, after).handle).toBe(41);
  });

  it("prefers an active duplicate, then the lowest handle; null when unknown", () => {
    const cat = [
      { handle: 9, type: 1, name: "Acc" },
      { handle: 5, type: 1, name: "Acc" },
    ];
    expect(resolveKey("1:Acc", listSensors(cat, [])).handle).toBe(5);
    expect(resolveKey("1:Acc", listSensors(cat, [{ handle: 9, type: 1 }])).handle).toBe(9);
    expect(resolveKey("4:Nope", listSensors(cat, []))).toBeNull();
  });
});

describe("defaults and helpers", () => {
  it("offers active vector sensors, accelerometer and gyroscope first", () => {
    const rows = listSensors(catalog, [{ handle: 3, type: 4 }, { handle: 1, type: 1 }, { handle: 8, type: 5 }]);
    expect(defaultPinKeys(rows)).toEqual(["1:LSM6DSO Accelerometer", "4:LSM6DSO Gyroscope"]);
  });

  it("formats and parses keys", () => {
    expect(sensorKey(4, "Gyro: A")).toBe("4:Gyro: A");
    expect(keyLabel("4:Gyro: A")).toBe("Gyro: A");
    expect(keyLabel(PIN_3D)).toBe("3D Orientation");
  });
});

describe("review fixes: pins made before the catalog arrived", () => {
  it("a generic key (type:signal name) still resolves once the vendor catalog lands", () => {
    const early = listSensors([], [{ handle: 1, type: 1 }]);
    const key = early[0].key; // "1:Acceleration"
    const late = listSensors([{ handle: 1, type: 1, name: "LSM6DSO Accelerometer" }], [{ handle: 1, type: 1 }]);
    expect(resolveKey(key, late).handle).toBe(1);
    expect(pinKeyFor(late[0], [key, "3d"], late)).toBe(key);
    expect(pinKeyFor(late[0], ["3d"], late)).toBeNull();
  });
});

describe("pins across phones", () => {
  const s25 = listSensors(
    [
      { handle: 10, type: 1, name: "LSM6DSV Accelerometer" },
      { handle: 11, type: 4, name: "LSM6DSV Gyroscope" },
      { handle: 12, type: 5, name: "TCS Light" },
    ],
    [{ handle: 10, type: 1 }, { handle: 11, type: 4 }]
  );

  it("a pin made on another phone attaches to this phone's sensor of the same type", () => {
    expect(resolveKey("1:Synthetic Accelerometer", s25).handle).toBe(10);
    expect(resolveKey("4:Synthetic Gyroscope", s25).handle).toBe(11);
    expect(resolveKey("9:Synthetic Gravity", s25)).toBeNull();
  });

  it("an exact match wins over a same-type fallback", () => {
    const two = listSensors(
      [{ handle: 1, type: 1, name: "Acc A" }, { handle: 2, type: 1, name: "Acc B" }],
      [{ handle: 1, type: 1 }, { handle: 2, type: 1 }]
    );
    expect(resolveKey("1:Acc B", two).handle).toBe(2);
    expect(resolveKey("1:Other", two).handle).toBe(1);
  });

  it("pinKeyFor reports the pin that resolves to the row", () => {
    const acc = s25.find((r) => r.handle === 10);
    expect(pinKeyFor(acc, ["3d", "1:Synthetic Accelerometer"], s25)).toBe("1:Synthetic Accelerometer");
    expect(pinKeyFor(s25.find((r) => r.handle === 12), ["1:Synthetic Accelerometer"], s25)).toBeNull();
  });
});
