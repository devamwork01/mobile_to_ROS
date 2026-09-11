import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { getByType } from "../telemetry/store.js";
import { androidToThree } from "../lib/orient.js";
import { AXIS } from "../telemetry/signals.js";

const ORI_TYPES = [11, 15, 20]; // rotation vector / game / geomagnetic
const GRAV_TYPES = [9, 1]; // gravity preferred, else accelerometer

function makeLabel(text, color) {
  const c = document.createElement("canvas");
  c.width = c.height = 64;
  const g = c.getContext("2d");
  g.fillStyle = color;
  g.font = "bold 44px system-ui, sans-serif";
  g.textAlign = "center";
  g.textBaseline = "middle";
  g.fillText(text, 32, 34);
  const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: new THREE.CanvasTexture(c), transparent: true, depthTest: false }));
  sp.scale.set(0.3, 0.3, 0.3);
  return sp;
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

  useEffect(() => {
    const el = mount.current;
    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 100);
    camera.position.set(1.9, 1.5, 2.4);
    camera.lookAt(0, 0, 0);
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(window.devicePixelRatio || 1);
    el.appendChild(renderer.domElement);
    renderer.domElement.style.position = "absolute";
    renderer.domElement.style.inset = "0";

    scene.add(new THREE.HemisphereLight(0x9fbaff, 0x0a0d12, 0.85));
    const key = new THREE.DirectionalLight(0xffffff, 1.1);
    key.position.set(2, 4, 3);
    scene.add(key);

    const grid = new THREE.GridHelper(8, 16, 0x2a313c, 0x1a2029);
    grid.position.y = -1.25;
    scene.add(grid);

    // phone (local axes == device axes: +X right, +Y top, +Z out of screen)
    const phone = new THREE.Group();
    scene.add(phone);
    const body = new THREE.MeshStandardMaterial({ color: 0x2b323d, metalness: 0.75, roughness: 0.35 });
    const screen = new THREE.MeshStandardMaterial({ color: 0x0e1524, metalness: 0.2, roughness: 0.1, emissive: 0x0a2044, emissiveIntensity: 0.5 });
    const faces = [body, body, body, body, screen, body];
    const mesh = new THREE.Mesh(new THREE.BoxGeometry(0.72, 1.44, 0.09), faces);
    phone.add(mesh);
    const dot = new THREE.Mesh(new THREE.CircleGeometry(0.045, 24), new THREE.MeshBasicMaterial({ color: 0x0a0d12 }));
    dot.position.set(0, 0.58, 0.046);
    phone.add(dot);

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
    let raf = 0;
    const animate = () => {
      raf = requestAnimationFrame(animate);
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
      phone.quaternion.slerp(target, 0.3);

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
      renderer.render(scene, camera);
    };

    const resize = () => {
      const w = el.clientWidth || 400;
      const h = el.clientHeight || 320;
      renderer.setSize(w, h);
      camera.aspect = w / h;
      camera.updateProjectionMatrix();
    };
    const ro = new ResizeObserver(resize);
    ro.observe(el);
    resize();
    animate();

    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
      renderer.dispose();
      el.removeChild(renderer.domElement);
    };
  }, []);

  const T = (k) => setTog((s) => ({ ...s, [k]: !s[k] }));
  return (
    <div className="flex flex-col h-full">
      <div ref={mount} className="relative flex-1 min-h-[300px] rounded-xl overflow-hidden bg-[radial-gradient(120%_120%_at_50%_0%,rgba(61,123,253,0.08),rgba(0,0,0,0)_60%)]" />
      <div className="flex flex-wrap gap-x-5 gap-y-2 pt-3">
        <Toggle label="World Frame" on={tog.world} onClick={() => T("world")} />
        <Toggle label="Body Frame" on={tog.body} onClick={() => T("body")} />
        <Toggle label="Sensor Vector" on={tog.vector} onClick={() => T("vector")} />
        <Toggle label="Labels" on={tog.labels} onClick={() => T("labels")} />
      </div>
    </div>
  );
}
