// Android rotation-vector quaternion (device -> ENU world) mapped into Three.js.
// ENU is Z-up, Three is Y-up: a single fixed -90deg-about-X basis change.
// (Ported verbatim from the validated vanilla implementation; math unchanged.)
export function androidToThree(x, y, z, w) {
  const s = Math.sin(-Math.PI / 4);
  const c = Math.cos(-Math.PI / 4);
  const b = { x: s, y: 0, z: 0, w: c }; // Rx(-90deg)
  const n = Math.hypot(x, y, z, w) || 1;
  const a = { x: x / n, y: y / n, z: z / n, w: w / n };
  return {
    w: b.w * a.w - b.x * a.x - b.y * a.y - b.z * a.z,
    x: b.w * a.x + b.x * a.w + b.y * a.z - b.z * a.y,
    y: b.w * a.y - b.x * a.z + b.y * a.w + b.z * a.x,
    z: b.w * a.z + b.x * a.y - b.y * a.x + b.z * a.w,
  };
}

// Roll / pitch / yaw (degrees) from a device->world quaternion (Android convention).
export function eulerFromQuat(x, y, z, w) {
  const R = [
    1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
    2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
    2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y),
  ];
  const clamp = (v) => Math.max(-1, Math.min(1, v));
  const deg = (r) => (r * 180) / Math.PI;
  return {
    roll: deg(Math.atan2(-R[6], R[8])),
    pitch: deg(Math.asin(clamp(-R[7]))),
    yaw: ((deg(Math.atan2(R[1], R[4])) % 360) + 360) % 360,
  };
}
