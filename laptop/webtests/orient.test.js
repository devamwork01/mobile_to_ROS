// Node tests for web/orient.js — verifies the Android(ENU) -> Three.js axis
// mapping is exactly right (no silent swaps). Run: node webtests/orient.test.js
const assert = require("assert");
const O = require("../web/orient.js");

const EPS = 1e-6;
const close = (a, b, m) => assert.ok(Math.abs(a - b) <= EPS, `${m}: expected ${b}, got ${a}`);
const vclose = (v, x, y, z, m) => { close(v.x, x, m + ".x"); close(v.y, y, m + ".y"); close(v.z, z, m + ".z"); };

// 1. Identity Android quaternion -> B = Rx(-90deg).
const q0 = O.androidToThree(0, 0, 0, 1);
close(q0.x, -Math.SQRT1_2, "B.x");
close(q0.y, 0, "B.y");
close(q0.z, 0, "B.z");
close(q0.w, Math.SQRT1_2, "B.w");

// 2. With device == ENU (identity), device axes map: East->+X, North->-Z, Up->+Y.
vclose(O.rotateVec(q0, { x: 1, y: 0, z: 0 }), 1, 0, 0, "deviceX(East)");
vclose(O.rotateVec(q0, { x: 0, y: 1, z: 0 }), 0, 0, -1, "deviceY(North)");
vclose(O.rotateVec(q0, { x: 0, y: 0, z: 1 }), 0, 1, 0, "deviceZ(Up)");

// 3. orientation(identity) ~ 0.
const o = O.orientation({ x: 0, y: 0, z: 0, w: 1 });
close(o.azimuth, 0, "az0");
close(o.pitch, 0, "pitch0");
close(o.roll, 0, "roll0");

// 4. 90deg yaw about world Z -> |azimuth| = pi/2.
const yaw = O.orientation({ x: 0, y: 0, z: Math.SQRT1_2, w: Math.SQRT1_2 });
assert.ok(Math.abs(Math.abs(yaw.azimuth) - Math.PI / 2) < 1e-6, `yaw azimuth ${yaw.azimuth}`);

// 5. Result stays a unit quaternion.
const n = Math.hypot(q0.x, q0.y, q0.z, q0.w);
close(n, 1, "unit");

console.log("orient.js: all checks passed");
