import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { RoundedBoxGeometry } from "three/examples/jsm/geometries/RoundedBoxGeometry.js";
import { RoomEnvironment } from "three/examples/jsm/environments/RoomEnvironment.js";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { getByType } from "../telemetry/store.js";
import { androidToThree } from "../lib/orient.js";
import { AXIS } from "../telemetry/signals.js";
import { frameIntervalMs, report } from "../lib/renderBudget.js";
import { useThemeStamp } from "../lib/theme.js";

const ORI_TYPES = [11, 15, 20]; // rotation vector / game / geomagnetic
const GRAV_TYPES = [9, 1]; // gravity preferred, else accelerometer

function makeLabel(text, color) {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const g = c.getContext("2d");
  // Accepts "#rrggbb" or a THREE-style 0xrrggbb number (a raw number is not a valid canvas color).
  g.fillStyle = typeof color === "number" ? `#${color.toString(16).padStart(6, "0")}` : color;
  g.font = "bold 44px system-ui, sans-serif";
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.fillText(text, 32, 34);
  const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthTest: false }));
  sp.scale.set(0.3, 0.3, 0.3);
  return sp;
}

// Floor grid tinted from the active theme's tokens (cyan-leaning lines on either background).
function themedGrid() {
  const css = getComputedStyle(document.documentElement);
  const rgb = (name) => {
    const [r, g, b] = css.getPropertyValue(name).trim().split(/\s+/).map(Number);
    return new THREE.Color().setRGB(r / 255, g / 255, b / 255, THREE.SRGBColorSpace); // CSS tokens are sRGB
  };
  const accent = rgb("--accent");
  const center = rgb("--line2").lerp(accent, 0.35);
  const lines = rgb("--line").lerp(accent, 0.12);
  const grid = new THREE.GridHelper(8, 16, center, lines);
  grid.material.transparent = true;
  grid.material.opacity = 0.45; // a backdrop: the phone and axes stay the focus
  grid.position.y = -1.25;
  return grid;
}

// Rounded-rectangle outline centered on the origin (the phone's face / profile).
function roundedRect(w, h, r) {
  const x = -w / 2, y = -h / 2;
  const s = new THREE.Shape();
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

// ShapeGeometry UVs are raw coordinates; map them to 0..1 across the shape so textures fit.
function fitUv(geo, w, h) {
  const pos = geo.attributes.position;
  const uv = geo.attributes.uv;
  for (let i = 0; i < pos.count; i++) uv.setXY(i, (pos.getX(i) + w / 2) / w, (pos.getY(i) + h / 2) / h);
  uv.needsUpdate = true;
  return geo;
}

// Subtle glowing "screen content" drawn to a canvas, used as an additive overlay.
function makeScreenTexture() {
  const c = document.createElement("canvas");
  c.width = 256;
  c.height = 512;
  const g = c.getContext("2d");
  g.clearRect(0, 0, 256, 512);
  const rg = g.createRadialGradient(128, 160, 8, 128, 160, 280);
  rg.addColorStop(0, "rgba(34,211,238,0.55)");
  rg.addColorStop(0.6, "rgba(34,211,238,0.10)");
  rg.addColorStop(1, "rgba(34,211,238,0)");
  g.fillStyle = rg;
  g.fillRect(0, 0, 256, 512);
  g.textAlign = "center";
  g.fillStyle = "rgba(232,237,244,0.92)";
  g.font = "bold 25px Inter, system-ui, sans-serif";
  g.fillText("SENSORSTREAM", 128, 300);
  g.fillStyle = "rgba(34,211,238,0.95)";
  g.font = "bold 15px Inter, system-ui, sans-serif";
  g.fillText("P R O", 128, 324);
  g.strokeStyle = "rgba(63,208,122,0.55)";
  g.lineWidth = 2.5;
  g.beginPath();
  for (let x = 0; x <= 256; x += 3) {
    const env = Math.exp(-Math.abs(x - 128) / 110);
    const y = 392 + Math.sin(x / 15) * 20 * env;
    x === 0 ? g.moveTo(x, y) : g.lineTo(x, y);
  }
  g.stroke();
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  return tex;
}

function Toggle({ label, color, on, onClick }) {
  return (
    <button onClick={onClick} className="flex items-center gap-2 text-xs text-muted hover:text-fg">
      <span className={`w-8 h-4 rounded-full p-0.5 transition-colors ${on ? "bg-accent" : "bg-line2"}`}>
        <span className={`block w-3 h-3 rounded-full bg-white transition-transform ${on ? "translate-x-4" : ""}`} />
      </span>
      {color && <span className="w-2 h-2 rounded-full" style={{ background: color }} />}
      {label}
    </button>
  );
}

export default function Phone3D() {
  const mount = useRef(null);
  const [tog, setTog] = useState({ world: true, body: true, vector: true, labels: true });
  const togRef = useRef(tog);
  togRef.current = tog;
  const cmdRef = useRef(null); // "recenter" | "reset", consumed by the render loop
  const [recentered, setRecentered] = useState(false);
  const gridRef = useRef(null); // { scene, grid } so a theme switch can re-tint the floor
  const themeStamp = useThemeStamp();

  useEffect(() => {
    const el = mount.current;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(42, 1, 0.1, 100);
    camera.position.set(1.85, 1.35, 2.4);
    camera.lookAt(0, 0, 0);

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, powerPreference: "high-performance" });
    renderer.toneMapping = THREE.ACESFilmicToneMapping;
    renderer.toneMappingExposure = 1.05;
    renderer.shadowMap.enabled = true;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    el.appendChild(renderer.domElement);
    renderer.domElement.style.position = "absolute";
    renderer.domElement.style.inset = "0";

    // Drag to orbit the camera, wheel to zoom (pan disabled so the model stays centered). The phone
    // still shows the device's orientation and the world frame stays fixed — only the viewpoint moves.
    const controls = new OrbitControls(camera, renderer.domElement);
    controls.enableDamping = true;
    controls.dampingFactor = 0.08;
    controls.enablePan = false;
    controls.rotateSpeed = 0.9;
    controls.minDistance = 1.5;
    controls.maxDistance = 6;
    controls.target.set(0, 0, 0);
    controls.update();

    // Double-click the 3D to reset to the default view (camera + absolute orientation), matching
    // the phone's double-tap. Same action as the "Reset view" button.
    const onDblClick = () => { cmdRef.current = "reset"; setRecentered(false); };
    renderer.domElement.addEventListener("dblclick", onDblClick);

    // Image-based lighting (procedural, offline) for realistic metal/glass reflections.
    const pmrem = new THREE.PMREMGenerator(renderer);
    scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;

    scene.add(new THREE.HemisphereLight(0x9fbaff, 0x0a0d12, 0.35));
    const key = new THREE.DirectionalLight(0xffffff, 1.6);
    key.position.set(2.5, 4.5, 3);
    key.castShadow = true;
    key.shadow.mapSize.set(512, 512);
    key.shadow.camera.near = 0.5;
    key.shadow.camera.far = 15;
    key.shadow.camera.left = -3;
    key.shadow.camera.right = 3;
    key.shadow.camera.top = 3;
    key.shadow.camera.bottom = -3;
    key.shadow.bias = -0.0004;
    key.shadow.radius = 4;
    scene.add(key);
    const rim = new THREE.DirectionalLight(0x22d3ee, 0.8);
    rim.position.set(-3, 1.5, -2.5);
    scene.add(rim);

    const grid = themedGrid();
    scene.add(grid);
    gridRef.current = { scene, grid };

    // soft contact shadow catcher
    const ground = new THREE.Mesh(
      new THREE.PlaneGeometry(8, 8),
      new THREE.ShadowMaterial({ opacity: 0.32 })
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -1.248;
    ground.receiveShadow = true;
    scene.add(ground);

    // phone (local axes == device axes: +X right, +Y top, +Z out of screen)
    const phone = new THREE.Group();
    scene.add(phone);

    // --- Phone model: a modern flagship silhouette (rounded-rectangle profile extruded with soft
    // beveled edges), edge-to-edge glass, side buttons, a vertical rear camera column, and the
    // mission-control look: titanium-blue body with a glowing cyan rim.
    const W = 0.74, H = 1.52, T = 0.082; // outer size (device axes: X width, Y height, Z thickness)
    const R = 0.11; // corner radius of the outline
    const BEVEL = 0.013; // how far the rounded edge rolls in, both across the face and the thickness
    const D = T - 2 * BEVEL; // straight side-wall height
    const FRONT = T / 2; // front glass plane (+Z, screen side)

    // "Titanium silverblue": a light brushed-metal frame that reads clearly on the dark scene
    const titanium = new THREE.MeshPhysicalMaterial({
      color: 0x9aa8ba,
      metalness: 1.0,
      roughness: 0.3,
      clearcoat: 0.5,
      clearcoatRoughness: 0.2,
      envMapIntensity: 1.35,
    });
    const glass = new THREE.MeshPhysicalMaterial({
      color: 0x030509,
      metalness: 0.2,
      roughness: 0.08,
      clearcoat: 1.0,
      clearcoatRoughness: 0.04,
      envMapIntensity: 1.5,
    });
    const backGlass = new THREE.MeshPhysicalMaterial({
      color: 0x4a5a70,
      metalness: 0.45,
      roughness: 0.34,
      clearcoat: 1.0,
      clearcoatRoughness: 0.12,
      envMapIntensity: 1.1,
    });

    // body: profile inset by the bevel, so bevel + profile = the outer size
    const body = new THREE.Mesh(
      new THREE.ExtrudeGeometry(roundedRect(W - 2 * BEVEL, H - 2 * BEVEL, R - BEVEL), {
        depth: D,
        bevelEnabled: true,
        bevelThickness: BEVEL,
        bevelSize: BEVEL,
        bevelSegments: 6,
        curveSegments: 20,
      }),
      titanium
    );
    body.geometry.translate(0, 0, -D / 2);
    body.castShadow = true;
    body.receiveShadow = true;
    phone.add(body);

    // front glass (nearly edge to edge) and a satin back panel
    const face = (w, h, r, mat, z, flip = false) => {
      const m = new THREE.Mesh(fitUv(new THREE.ShapeGeometry(roundedRect(w, h, r), 24), w, h), mat);
      m.position.z = z;
      if (flip) m.rotation.y = Math.PI;
      phone.add(m);
      return m;
    };
    face(W - 0.03, H - 0.03, R - 0.015, glass, FRONT + 0.0006);
    face(W - 0.03, H - 0.03, R - 0.015, backGlass, -FRONT - 0.0006, true);

    // glowing screen content (additive overlay inside a thin bezel)
    const content = face(W - 0.07, H - 0.07, R - 0.035,
      new THREE.MeshBasicMaterial({ map: makeScreenTexture(), transparent: true, blending: THREE.AdditiveBlending, depthWrite: false, opacity: 0.95 }),
      FRONT + 0.0012);
    content.renderOrder = 1;

    // front punch-hole camera
    const dot = new THREE.Mesh(new THREE.CircleGeometry(0.02, 24), new THREE.MeshBasicMaterial({ color: 0x010203 }));
    dot.position.set(0, H / 2 - 0.075, FRONT + 0.0016);
    phone.add(dot);

    // side buttons (right edge): volume rocker above the power key
    for (const [y, len] of [[0.36, 0.2], [0.14, 0.1]]) {
      const b = new THREE.Mesh(new RoundedBoxGeometry(0.012, len, 0.026, 2, 0.005), titanium);
      b.position.set(W / 2 + 0.003, y, 0);
      phone.add(b);
    }

    // rear cameras: three lenses in a vertical column plus a flash, like current flagships
    const ringMat = new THREE.MeshStandardMaterial({ color: 0x5b626c, metalness: 1, roughness: 0.3 });
    const lensMat = new THREE.MeshPhysicalMaterial({ color: 0x05070b, metalness: 0.4, roughness: 0.05, clearcoat: 1 });
    const camX = -W / 2 + 0.15;
    for (const y of [H / 2 - 0.16, H / 2 - 0.3, H / 2 - 0.44]) {
      const ring = new THREE.Mesh(new THREE.CylinderGeometry(0.056, 0.056, 0.014, 32), ringMat);
      ring.rotation.x = Math.PI / 2;
      ring.position.set(camX, y, -FRONT - 0.006);
      ring.castShadow = true;
      phone.add(ring);
      const lens = new THREE.Mesh(new THREE.CylinderGeometry(0.042, 0.042, 0.004, 32), lensMat);
      lens.rotation.x = Math.PI / 2;
      lens.position.set(camX, y, -FRONT - 0.0135);
      phone.add(lens);
    }
    const flash = new THREE.Mesh(new THREE.CircleGeometry(0.018, 20), new THREE.MeshBasicMaterial({ color: 0xd9c9a3 }));
    flash.position.set(camX + 0.12, H / 2 - 0.16, -FRONT - 0.0008);
    flash.rotation.y = Math.PI;
    phone.add(flash);

    // mission-control accent: a glowing cyan rim around the silhouette, front and back. A thin
    // tube rather than a line: WebGL draws lines 1 px wide whatever linewidth says.
    const outlineMat = new THREE.MeshBasicMaterial({ color: 0x22d3ee, toneMapped: false });
    const outlinePts = roundedRect(W + 0.006, H + 0.006, R + 0.003).getPoints(24);
    for (const z of [D / 2, -D / 2]) {
      const curve = new THREE.CatmullRomCurve3(outlinePts.map((pt) => new THREE.Vector3(pt.x, pt.y, z)), true);
      phone.add(new THREE.Mesh(new THREE.TubeGeometry(curve, 480, 0.0055, 8, true), outlineMat));
    }

    const arrow = (parent, dir, len, color, head = 0.16) => {
      const a = new THREE.ArrowHelper(dir.clone().normalize(), new THREE.Vector3(), len, color, head, head * 0.6);
      parent.add(a);
      return a;
    };
    const label = (parent, text, pos, color) => {
      const sp = makeLabel(text, color);
      sp.position.copy(pos);
      parent.add(sp);
      return sp;
    };

    // device axes (rotate with phone)
    const dev = new THREE.Group();
    phone.add(dev);
    arrow(dev, new THREE.Vector3(1, 0, 0), 1.0, AXIS.X);
    arrow(dev, new THREE.Vector3(0, 1, 0), 1.0, AXIS.Y);
    arrow(dev, new THREE.Vector3(0, 0, 1), 1.0, AXIS.Z);
    const devLabels = new THREE.Group();
    dev.add(devLabels);
    label(devLabels, "X", new THREE.Vector3(1.14, 0, 0), AXIS.X);
    label(devLabels, "Y", new THREE.Vector3(0, 1.14, 0), AXIS.Y);
    label(devLabels, "Z", new THREE.Vector3(0, 0, 1.14), AXIS.Z);

    // gravity/sensor vector in device frame
    const vec = arrow(phone, new THREE.Vector3(0, -1, 0), 1.15, 0xff5cf0, 0.14);

    // world ENU axes (fixed): E->+X, N->-Z, U->+Y
    const world = new THREE.Group();
    scene.add(world);
    arrow(world, new THREE.Vector3(1, 0, 0), 1.35, 0xe0863a);
    arrow(world, new THREE.Vector3(0, 0, -1), 1.35, 0x39c5cf);
    arrow(world, new THREE.Vector3(0, 1, 0), 1.35, 0xe3b341);
    const worldLabels = new THREE.Group();
    world.add(worldLabels);
    label(worldLabels, "E", new THREE.Vector3(1.48, 0, 0), 0xe0863a);
    label(worldLabels, "N", new THREE.Vector3(0, 0, -1.48), 0x39c5cf);
    label(worldLabels, "U", new THREE.Vector3(0, 1.48, 0), 0xe3b341);

    const target = new THREE.Quaternion();
    // Recenter reference (display-only): show every pose relative to a captured one.
    const refQuat = new THREE.Quaternion();
    const invRef = new THREE.Quaternion();
    const shown = new THREE.Quaternion();
    let haveRef = false;
    let raf = 0;
    let lastRender = 0;

    // Render budget comes from the shared scheduler: ~30 fps idle, ~15 fps while scrolling
    // (degrade, never freeze). Only skip entirely when scrolled off-screen (§8).
    let visible = true;
    const io = new IntersectionObserver(([e]) => { visible = e.isIntersecting; }, { threshold: 0.01 });
    io.observe(el);

    const animate = (now) => {
      raf = requestAnimationFrame(animate);
      if (now - lastRender < frameIntervalMs("3d")) return;
      lastRender = now;
      if (!visible) return; // off-screen only — visible content keeps rendering during scroll
      const t = togRef.current;
      dev.visible = t.body;
      world.visible = t.world;
      vec.visible = t.vector;
      devLabels.visible = worldLabels.visible = t.labels;

      let ori;
      for (const ty of ORI_TYPES) {
        const r = getByType(ty);
        if (r && r.v && r.v.length >= 3) { ori = r.v; break; }
      }
      if (ori) {
        const x = ori[0], y = ori[1], z = ori[2];
        const w = ori.length >= 4 ? ori[3] : Math.sqrt(Math.max(0, 1 - x * x - y * y - z * z));
        const q = androidToThree(x, y, z, w);
        target.set(q.x, q.y, q.z, q.w);
      }
      // Recenter: capture the current pose as reference, then show every pose relative to it
      // (ref^-1 * q) and rotate the world frame the same way so phone and world stay consistent.
      if (cmdRef.current === "recenter") { refQuat.copy(target); haveRef = true; cmdRef.current = null; }
      else if (cmdRef.current === "reset") {
        haveRef = false;
        // Snap the camera back to its start pose. Damping must be off for the reset frame, or
        // OrbitControls re-applies leftover drag momentum on the next update() and the camera
        // never actually returns to default.
        const damp = controls.enableDamping;
        controls.enableDamping = false;
        controls.reset();
        controls.enableDamping = damp;
        cmdRef.current = null;
      }
      if (haveRef) {
        invRef.copy(refQuat).invert();
        shown.copy(invRef).multiply(target);
        world.quaternion.copy(invRef);
      } else {
        shown.copy(target);
        world.quaternion.identity();
      }
      phone.quaternion.slerp(shown, 0.3);

      if (t.vector) {
        for (const ty of GRAV_TYPES) {
          const r = getByType(ty);
          if (r && r.v && r.v.length >= 3) {
            const len = Math.hypot(r.v[0], r.v[1], r.v[2]);
            if (len > 1e-6) vec.setDirection(new THREE.Vector3(r.v[0], r.v[1], r.v[2]).multiplyScalar(1 / len));
            break;
          }
        }
      }
      controls.update();
      renderer.render(scene, camera);
      report("3d");
    };

    let warnedBigBuffer = false;
    const resize = () => {
      const w = el.clientWidth || 400;
      const h = el.clientHeight || 320;
      // Cap the drawing-buffer width (CSS upscales) so the fragment cost stays
      // bounded on large / high-DPI screens — the fix for size-dependent lag.
      const dpr = Math.min(window.devicePixelRatio || 1, 1.5);
      const MAX_BUFFER_W = 1280;
      const pr = Math.min(dpr, MAX_BUFFER_W / Math.max(1, w));
      renderer.setPixelRatio(pr);
      renderer.setSize(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
      // §7 guard: warn once if a CSS regression lets the drawing buffer balloon again.
      const cv = renderer.domElement;
      if (!warnedBigBuffer && cv.width * cv.height > 600000) {
        warnedBigBuffer = true;
        console.warn(`[Phone3D] drawing buffer ${cv.width}x${cv.height} (>0.6 MP) — possible layout regression (ADR-001 §7).`);
      }
    };
    const ro = new ResizeObserver(resize);
    ro.observe(el);
    resize();
    animate();

    return () => {
      cancelAnimationFrame(raf);
      renderer.domElement.removeEventListener("dblclick", onDblClick);
      controls.dispose();
      ro.disconnect();
      io.disconnect();
      pmrem.dispose();
      scene.environment?.dispose();
      renderer.dispose();
      renderer.forceContextLoss(); // fully release the GPU context (avoid leaking contexts across remounts)
      el.removeChild(renderer.domElement);
    };
  }, []);

  useEffect(() => {
    const g = gridRef.current;
    if (!g || themeStamp === 0) return;
    g.scene.remove(g.grid);
    g.grid.geometry.dispose();
    g.grid.material.dispose();
    g.grid = themedGrid();
    g.scene.add(g.grid);
  }, [themeStamp]);

  const T = (k) => setTog((s) => ({ ...s, [k]: !s[k] }));
  const recenter = () => { cmdRef.current = "recenter"; setRecentered(true); };
  const resetView = () => { cmdRef.current = "reset"; setRecentered(false); };
  return (
    <div className="flex flex-col h-full">
      <div ref={mount} className="relative flex-1 min-h-[300px] rounded-xl overflow-hidden bg-[radial-gradient(120%_120%_at_50%_0%,rgba(34,211,238,0.08),rgba(0,0,0,0)_60%)]" />
      <div className="flex items-center gap-2 pt-3">
        <button onClick={recenter} className="btn-ghost text-xs" title="Zero the view to the phone's current pose">
          Recenter
        </button>
        <button onClick={resetView} className="btn-ghost text-xs" title="Reset the camera and show absolute orientation">
          Reset view
        </button>
        {recentered && <span className="text-[11px] text-faint">relative to captured pose</span>}
      </div>
      <div className="flex flex-wrap gap-x-5 gap-y-2 pt-2">
        <Toggle label="World Frame" on={tog.world} onClick={() => T("world")} />
        <Toggle label="Body Frame" on={tog.body} onClick={() => T("body")} />
        <Toggle label="Sensor Vector" on={tog.vector} onClick={() => T("vector")} />
        <Toggle label="Labels" on={tog.labels} onClick={() => T("labels")} />
      </div>
    </div>
  );
}
