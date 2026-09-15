// Theme store: persists the user's choice ("system" | "light" | "dark") and stamps
// data-theme on <html> so the CSS-variable palettes in index.css resolve accordingly.
// "system" stamps nothing and lets prefers-color-scheme decide. All localStorage access is
// wrapped so a private window / blocked storage can't break rendering.

const KEY = "ss-theme";
const VALID = new Set(["system", "light", "dark"]);

export function getStoredTheme() {
  try {
    const v = localStorage.getItem(KEY);
    return VALID.has(v) ? v : "system";
  } catch {
    return "system";
  }
}

export function applyTheme(pref) {
  const root = document.documentElement;
  if (pref === "light" || pref === "dark") {
    root.setAttribute("data-theme", pref);
  } else {
    root.removeAttribute("data-theme"); // system: media query decides
  }
}

export function setTheme(pref) {
  const p = VALID.has(pref) ? pref : "system";
  try {
    localStorage.setItem(KEY, p);
  } catch {
    /* ignore: storage may be unavailable */
  }
  applyTheme(p);
  return p;
}

// Apply the stored preference as early as possible (called from main before render).
export function initTheme() {
  const p = getStoredTheme();
  applyTheme(p);
  return p;
}
