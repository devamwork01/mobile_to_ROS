// UI-only signal metadata: maps the existing Android sensor type (already in the
// telemetry) to human-readable engineering names, units, category and an icon.
// The Android type stays available as secondary technical info. This never
// changes the sensor data — it only decides how to present it.

export const AXIS = { X: "#ff5c5c", Y: "#3fd07a", Z: "#4c8dff" }; // universal X/Y/Z

export const CATEGORY = {
  motion: "Motion",
  magnetic: "Magnetic",
  orientation: "Orientation",
  environment: "Environment",
  other: "Other",
};

// kind: "vector" (X/Y/Z + magnitude), "orientation" (quaternion), "scalar" (single value)
const S = {
  1: { name: "Acceleration", sub: "Accelerometer", type: "TYPE_ACCELEROMETER", unit: "m/s²", cat: "motion", kind: "vector", icon: "Activity" },
  10: { name: "Linear Acceleration", sub: "Linear Accelerometer", type: "TYPE_LINEAR_ACCELERATION", unit: "m/s²", cat: "motion", kind: "vector", icon: "Waves" },
  4: { name: "Angular Velocity", sub: "Gyroscope", type: "TYPE_GYROSCOPE", unit: "rad/s", cat: "motion", kind: "vector", icon: "Orbit" },
  9: { name: "Gravity", sub: "Gravity", type: "TYPE_GRAVITY", unit: "m/s²", cat: "motion", kind: "vector", icon: "ArrowDownToLine" },
  2: { name: "Magnetic Field", sub: "Magnetometer", type: "TYPE_MAGNETIC_FIELD", unit: "µT", cat: "magnetic", kind: "vector", icon: "Magnet" },
  14: { name: "Magnetic Field (uncal)", sub: "Magnetometer", type: "TYPE_MAGNETIC_FIELD_UNCALIBRATED", unit: "µT", cat: "magnetic", kind: "vector", icon: "Magnet" },
  11: { name: "Orientation", sub: "Rotation Vector", type: "TYPE_ROTATION_VECTOR", unit: "quat", cat: "orientation", kind: "orientation", icon: "Compass" },
  15: { name: "Orientation (Game)", sub: "Game Rotation Vector", type: "TYPE_GAME_ROTATION_VECTOR", unit: "quat", cat: "orientation", kind: "orientation", icon: "Compass" },
  20: { name: "Orientation (Geomagnetic)", sub: "Geomagnetic Rotation Vector", type: "TYPE_GEOMAGNETIC_ROTATION_VECTOR", unit: "quat", cat: "orientation", kind: "orientation", icon: "Compass" },
  6: { name: "Atmospheric Pressure", sub: "Barometer", type: "TYPE_PRESSURE", unit: "hPa", cat: "environment", kind: "scalar", icon: "Gauge" },
  5: { name: "Ambient Light", sub: "Light Sensor", type: "TYPE_LIGHT", unit: "lx", cat: "environment", kind: "scalar", icon: "Sun" },
  13: { name: "Ambient Temperature", sub: "Thermometer", type: "TYPE_AMBIENT_TEMPERATURE", unit: "°C", cat: "environment", kind: "scalar", icon: "Thermometer" },
  12: { name: "Relative Humidity", sub: "Hygrometer", type: "TYPE_RELATIVE_HUMIDITY", unit: "%", cat: "environment", kind: "scalar", icon: "Droplets" },
  8: { name: "Proximity", sub: "Proximity Sensor", type: "TYPE_PROXIMITY", unit: "cm", cat: "environment", kind: "scalar", icon: "Ruler" },
};

export function signalMeta(type) {
  return (
    S[type] || {
      name: `Type ${type}`,
      sub: "Sensor",
      type: `TYPE_${type}`,
      unit: "",
      cat: "other",
      kind: type >= 3 ? "vector" : "scalar",
      icon: "CircleDot",
    }
  );
}

export const magnitude = (v) => (v && v.length >= 3 ? Math.hypot(v[0], v[1], v[2]) : NaN);
