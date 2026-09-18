// Tests for signalMeta() naming — verifies vendor / unlisted sensor types get a real
// humanized name from the Android stringType instead of a bare "Type N".
// Run: node webtests/signals.test.mjs
import assert from "node:assert";
import { signalMeta } from "../webapp/src/telemetry/signals.js";

// Known standard types keep their curated names (unchanged).
assert.equal(signalMeta(1).name, "Acceleration", "accel curated name");
assert.equal(signalMeta(4).name, "Angular Velocity", "gyro curated name");
assert.equal(signalMeta(11).kind, "orientation", "rotation vector kind");

// Unlisted standard types: humanize the stringType rather than showing "Type 19".
assert.equal(
  signalMeta(19, "android.sensor.step_counter").name,
  "Step Counter",
  "step counter humanized from stringType"
);
assert.equal(
  signalMeta(21, "android.sensor.heart_rate").name,
  "Heart Rate",
  "heart rate humanized"
);

// Vendor sensors (non-android namespace, huge type ids): still get a clean name.
assert.equal(
  signalMeta(65572, "com.samsung.sensor.super_accelerometer").name,
  "Super Accelerometer",
  "vendor sensor humanized from stringType tail"
);

// No stringType available -> falls back to the old "Type N" label (nothing better to show).
assert.equal(signalMeta(65572).name, "Type 65572", "fallback when stringType absent");

console.log("signals.test.mjs: all assertions passed");
