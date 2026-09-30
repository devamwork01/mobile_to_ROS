import { describe, it, expect } from "vitest";
import { createLayoutStore, loadLayout, LAYOUT_KEY, DEFAULT_WINDOW } from "./layout.js";

function memStorage(init = {}) {
  const data = { ...init };
  return { data, getItem: (k) => (k in data ? data[k] : null), setItem: (k, v) => { data[k] = String(v); } };
}
const throwing = { getItem() { throw new Error("blocked"); }, setItem() { throw new Error("blocked"); } };

describe("layout defaults and loading", () => {
  it("starts with the 3D panel pinned", () => {
    const s = createLayoutStore(memStorage());
    expect(s.get().pins).toEqual([{ key: "3d", window: DEFAULT_WINDOW, paused: false, collapsed: false }]);
    expect(s.get().maximized).toBeNull();
  });

  it("falls back to defaults for corrupt or old-version storage", () => {
    expect(loadLayout(memStorage({ [LAYOUT_KEY]: "{not json" })).pins[0].key).toBe("3d");
    expect(loadLayout(memStorage({ [LAYOUT_KEY]: JSON.stringify({ v: 0, pins: [] }) })).pins[0].key).toBe("3d");
  });

  it("sanitises stored pins: dedupes keys and fixes bad windows", () => {
    const raw = JSON.stringify({ v: 1, pins: [{ key: "1:A", window: 7 }, { key: "1:A", window: 30 }], pickerCollapsed: true, seeded: true });
    const s = loadLayout(memStorage({ [LAYOUT_KEY]: raw }));
    expect(s.pins).toEqual([{ key: "1:A", window: DEFAULT_WINDOW, paused: false, collapsed: false }]);
    expect(s.pickerCollapsed).toBe(true);
  });

  it("works when storage throws", () => {
    const s = createLayoutStore(throwing);
    s.pin("1:A");
    expect(s.get().pins.map((p) => p.key)).toEqual(["3d", "1:A"]);
  });
});

describe("layout actions", () => {
  it("round-trips through storage, without the maximised state", () => {
    const st = memStorage();
    const a = createLayoutStore(st);
    a.pin("1:A");
    a.setWindow("1:A", 30);
    a.setPaused("1:A", true);
    a.toggleCollapsed("3d");
    a.maximize("1:A");
    const b = createLayoutStore(st);
    expect(b.get().pins).toEqual([
      { key: "3d", window: 10, paused: false, collapsed: true },
      { key: "1:A", window: 30, paused: true, collapsed: false },
    ]);
    expect(b.get().maximized).toBeNull();
  });

  it("pin is idempotent; unpin clears maximised; maximise ignores unknown keys", () => {
    const s = createLayoutStore(memStorage());
    s.pin("1:A");
    s.pin("1:A");
    s.maximize("1:A");
    s.maximize("9:Nope");
    expect(s.get().maximized).toBe("1:A");
    s.unpin("1:A");
    expect(s.get().pins.map((p) => p.key)).toEqual(["3d"]);
    expect(s.get().maximized).toBeNull();
  });

  it("rejects windows outside the allowed set", () => {
    const s = createLayoutStore(memStorage());
    s.setWindow("3d", 45);
    expect(s.get().pins[0].window).toBe(DEFAULT_WINDOW);
  });

  it("seeds at most two defaults, once", () => {
    const s = createLayoutStore(memStorage());
    s.seedDefaults([]);
    expect(s.get().seeded).toBe(false);
    s.seedDefaults(["1:A", "4:G", "2:M"]);
    expect(s.get().pins.map((p) => p.key)).toEqual(["3d", "1:A", "4:G"]);
    s.unpin("1:A");
    s.seedDefaults(["1:A"]);
    expect(s.get().pins.map((p) => p.key)).toEqual(["3d", "4:G"]);
  });

  it("notifies subscribers on change", () => {
    const s = createLayoutStore(memStorage());
    let n = 0;
    const off = s.subscribe(() => n++);
    s.togglePicker();
    off();
    s.togglePicker();
    expect(n).toBe(1);
  });
});
