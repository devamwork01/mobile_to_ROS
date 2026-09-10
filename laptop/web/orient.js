// Orientation math shared by the 3D view and its node tests (UMD: browser global
// `Orient` or CommonJS module). Pure quaternion helpers — no Three.js dependency
// — so the coordinate mapping can be unit-tested off-browser.
//
// Frames:
//   Device (D): +X right, +Y top, +Z out of screen        (Android convention)
//   World  (W): ENU — +X East, +Y North, +Z Up            (Android rotation vector)
//   Three  (T): +X right, +Y up, +Z toward viewer         (Three.js default)
//
// The Android rotation-vector quaternion is R(D->W). Three is Y-up while ENU is
// Z-up, so the ONLY remap is a fixed basis change B = Rx(-90deg) mapping
// ENU->Three (East->+X, Up->+Y, North->-Z). The mesh orientation is B * qAndroid.
(function (root) {
  function axisAngle(ax, ay, az, angle) {
    const h = angle / 2, s = Math.sin(h);
    return { x: ax * s, y: ay * s, z: az * s, w: Math.cos(h) };
  }

  // Hamilton product a*b.
  function mul(a, b) {
    return {
      w: a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z,
      x: a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
      y: a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
      z: a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
    };
  }

  function normalize(q) {
    const n = Math.hypot(q.x, q.y, q.z, q.w) || 1;
    return { x: q.x / n, y: q.y / n, z: q.z / n, w: q.w / n };
  }

  // Rotate vector v={x,y,z} by unit quaternion q.
  function rotateVec(q, v) {
    const tx = 2 * (q.y * v.z - q.z * v.y);
    const ty = 2 * (q.z * v.x - q.x * v.z);
    const tz = 2 * (q.x * v.y - q.y * v.x);
    return {
      x: v.x + q.w * tx + (q.y * tz - q.z * ty),
      y: v.y + q.w * ty + (q.z * tx - q.x * tz),
      z: v.z + q.w * tz + (q.x * ty - q.y * tx),
    };
  }

  // ENU(Z-up) -> Three(Y-up): rotate -90deg about X.
  const B = axisAngle(1, 0, 0, -Math.PI / 2);

  // Android rotation-vector components (x,y,z,w) -> Three.js mesh quaternion.
  function androidToThree(x, y, z, w) {
    return mul(B, normalize({ x: x, y: y, z: z, w: w }));
  }

  // 3x3 row-major rotation matrix (device->world) from a unit quaternion.
  function toMatrix(q) {
    const x = q.x, y = q.y, z = q.z, w = q.w;
    return [
      1 - 2 * (y * y + z * z), 2 * (x * y - w * z),     2 * (x * z + w * y),
      2 * (x * y + w * z),     1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
      2 * (x * z - w * y),     2 * (y * z + w * x),     1 - 2 * (x * x + y * y),
    ];
  }

  // Azimuth/pitch/roll (radians), matching Android SensorManager.getOrientation.
  function orientation(q) {
    const R = toMatrix(q);
    const clamp = (v) => Math.max(-1, Math.min(1, v));
    return {
      azimuth: Math.atan2(R[1], R[4]),
      pitch: Math.asin(clamp(-R[7])),
      roll: Math.atan2(-R[6], R[8]),
    };
  }

  const api = { axisAngle, mul, normalize, rotateVec, androidToThree, toMatrix, orientation, B };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.Orient = api;
})(typeof window !== "undefined" ? window : globalThis);
