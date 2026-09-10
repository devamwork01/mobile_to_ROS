// 3D phone orientation view (Three.js r128, UMD global `THREE`). Driven by the
// rotation-vector quaternion streaming from the phone, mapped ENU->Three by
// window.Orient. Device axes (X/Y/Z, RGB) rotate with the phone; world axes
// (E/N/U) are fixed; an optional gravity/mag vector is shown in the device frame.
(function () {
  if (typeof THREE === "undefined") {
    const el = document.getElementById("viz3d");
    if (el) el.textContent = "Three.js failed to load (vendor/three.min.js)";
    return;
  }

  const DEVICE = { x: 0xf04747, y: 0x43b581, z: 0x4a90e2 }; // right/top/out-of-screen
  const WORLD = { e: 0xe0863a, n: 0x3ab0e0, u: 0xe0c93a };  // East/North/Up
  const container = document.getElementById("viz3d");

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(50, 1, 0.1, 100);
  camera.position.set(1.7, 1.35, 2.2);
  camera.lookAt(0, 0, 0);

  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  renderer.setPixelRatio(window.devicePixelRatio || 1);
  container.appendChild(renderer.domElement);

  scene.add(new THREE.AmbientLight(0xffffff, 0.75));
  const dir = new THREE.DirectionalLight(0xffffff, 0.8);
  dir.position.set(2, 3, 2);
  scene.add(dir);

  const grid = new THREE.GridHelper(6, 12, 0x2b3440, 0x20272f);
  grid.position.y = -1.2;
  scene.add(grid);

  // ---- text sprite labels ----
  function label(text, colorHex) {
    const c = document.createElement("canvas");
    c.width = c.height = 64;
    const g = c.getContext("2d");
    g.fillStyle = "#" + colorHex.toString(16).padStart(6, "0");
    g.font = "bold 44px system-ui, sans-serif";
    g.textAlign = "center";
    g.textBaseline = "middle";
    g.fillText(text, 32, 34);
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true }));
    sp.scale.set(0.28, 0.28, 0.28);
    return sp;
  }
  function addArrow(parent, dirVec, length, color) {
    const a = new THREE.ArrowHelper(dirVec.clone().normalize(), new THREE.Vector3(0, 0, 0), length, color, 0.18, 0.12);
    parent.add(a);
    return a;
  }
  function addAxisLabel(parent, text, pos, color) {
    const sp = label(text, color);
    sp.position.copy(pos);
    parent.add(sp);
    return sp;
  }

  // ---- phone mesh (local axes == device axes) ----
  const phone = new THREE.Group();
  scene.add(phone);

  const body = 0x2f3742, screen = 0x6ea8fe;
  const faces = [body, body, body, body, screen, body].map((c) =>
    new THREE.MeshStandardMaterial({ color: c, metalness: 0.1, roughness: 0.6 })
  ); // BoxGeometry face order: +X,-X,+Y,-Y,+Z(screen),-Z
  const mesh = new THREE.Mesh(new THREE.BoxGeometry(0.7, 1.4, 0.08), faces);
  phone.add(mesh);
  // small "camera" dot near the top of the screen to show which end is +Y
  const dot = new THREE.Mesh(
    new THREE.CircleGeometry(0.05, 24),
    new THREE.MeshBasicMaterial({ color: 0x0d1117 })
  );
  dot.position.set(0, 0.55, 0.041);
  phone.add(dot);

  // device axes (children of phone -> rotate with it)
  const devGroup = new THREE.Group();
  phone.add(devGroup);
  addArrow(devGroup, new THREE.Vector3(1, 0, 0), 1.0, DEVICE.x);
  addArrow(devGroup, new THREE.Vector3(0, 1, 0), 1.0, DEVICE.y);
  addArrow(devGroup, new THREE.Vector3(0, 0, 1), 1.0, DEVICE.z);
  addAxisLabel(devGroup, "X", new THREE.Vector3(1.12, 0, 0), DEVICE.x);
  addAxisLabel(devGroup, "Y", new THREE.Vector3(0, 1.12, 0), DEVICE.y);
  addAxisLabel(devGroup, "Z", new THREE.Vector3(0, 0, 1.12), DEVICE.z);

  // sensor vectors in device frame (gravity/mag) — child of phone
  const accelArrow = addArrow(phone, new THREE.Vector3(0, -1, 0), 1.1, 0xff5cf0);
  const magArrow = addArrow(phone, new THREE.Vector3(1, 0, 0), 1.1, 0xffa030);

  // world ENU axes (fixed) — ENU mapped to Three: E->+X, N->-Z, U->+Y
  const worldGroup = new THREE.Group();
  scene.add(worldGroup);
  addArrow(worldGroup, new THREE.Vector3(1, 0, 0), 1.3, WORLD.e);
  addArrow(worldGroup, new THREE.Vector3(0, 0, -1), 1.3, WORLD.n);
  addArrow(worldGroup, new THREE.Vector3(0, 1, 0), 1.3, WORLD.u);
  addAxisLabel(worldGroup, "E", new THREE.Vector3(1.42, 0, 0), WORLD.e);
  addAxisLabel(worldGroup, "N", new THREE.Vector3(0, 0, -1.42), WORLD.n);
  addAxisLabel(worldGroup, "U", new THREE.Vector3(0, 1.42, 0), WORLD.u);

  // ---- toggles ----
  const cb = (id) => document.getElementById(id);
  function applyToggles() {
    devGroup.visible = cb("t-device") ? cb("t-device").checked : true;
    worldGroup.visible = cb("t-world") ? cb("t-world").checked : true;
    accelArrow.visible = cb("t-accel") ? cb("t-accel").checked : true;
    magArrow.visible = cb("t-mag") ? cb("t-mag").checked : false;
  }
  ["t-device", "t-world", "t-accel", "t-mag"].forEach((id) => {
    const e = cb(id);
    if (e) e.addEventListener("change", applyToggles);
  });
  applyToggles();

  // ---- data wiring ----
  const target = new THREE.Quaternion(); // desired phone orientation (Three frame)
  const readout = document.getElementById("orient-readout");
  const ROT_TYPES = { 11: 1, 15: 1, 20: 1 }; // rotation vector, game, geomagnetic

  function setOrientation(v) {
    const x = v[0], y = v[1], z = v[2];
    const w = v.length >= 4 ? v[3] : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
    const q = Orient.androidToThree(x, y, z, w);
    target.set(q.x, q.y, q.z, q.w);
    if (readout) {
      const o = Orient.orientation(Orient.normalize({ x: x, y: y, z: z, w: w }));
      const deg = (r) => (r * 180) / Math.PI;
      const heading = ((deg(o.azimuth) % 360) + 360) % 360;
      readout.textContent =
        `q = [${x.toFixed(3)}, ${y.toFixed(3)}, ${z.toFixed(3)}, ${w.toFixed(3)}]   ` +
        `heading ${heading.toFixed(0)}°  pitch ${deg(o.pitch).toFixed(0)}°  roll ${deg(o.roll).toFixed(0)}°`;
    }
  }
  function setDeviceVector(arrow, v) {
    const len = Math.hypot(v[0], v[1], v[2]);
    if (len > 1e-6) arrow.setDirection(new THREE.Vector3(v[0], v[1], v[2]).multiplyScalar(1 / len));
  }

  if (window.SensorNet) {
    SensorNet.on("data", (msg) => {
      let gravity = null, accel = null;
      msg.records.forEach((r) => {
        if (ROT_TYPES[r.type] && r.v.length >= 3) setOrientation(r.v);
        else if (r.type === 9) gravity = r.v;     // gravity preferred for the "down" arrow
        else if (r.type === 1) accel = r.v;        // else raw accelerometer
        else if (r.type === 2 && r.v.length >= 3) setDeviceVector(magArrow, r.v);
      });
      const g = gravity || accel;
      if (g && g.length >= 3) setDeviceVector(accelArrow, g);
    });
    SensorNet.on("phone_disconnected", () => { target.identity(); });
  }

  // ---- resize + render loop ----
  function resize() {
    const w = container.clientWidth || 320;
    const h = container.clientHeight || 320;
    renderer.setSize(w, h); // updateStyle=true: constrain the canvas to its box
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  if (window.ResizeObserver) new ResizeObserver(resize).observe(container);
  else window.addEventListener("resize", resize);
  resize();

  (function animate() {
    requestAnimationFrame(animate);
    phone.quaternion.slerp(target, 0.35); // smooth toward the latest orientation
    renderer.render(scene, camera);
  })();
})();
