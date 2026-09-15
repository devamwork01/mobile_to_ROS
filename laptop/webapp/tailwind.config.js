/** @type {import('tailwindcss').Config} */
// SensorStream Pro design tokens. Dark-first, now theme-aware: every semantic color
// resolves through a CSS variable (an "R G B" triple defined per theme in index.css),
// so `bg-surface` / `text-fg` re-resolve for light or dark with NO markup changes and
// Tailwind opacity modifiers still work. The universal X=red / Y=green / Z=blue axis
// colors are fixed hex — identical in both themes everywhere.
const v = (name) => `rgb(var(--${name}) / <alpha-value>)`;
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  darkMode: ["class", '[data-theme="dark"]'],
  theme: {
    extend: {
      colors: {
        ink: v("ink"), // app background
        surface: {
          DEFAULT: v("surface"),
          2: v("surface-2"),
          3: v("surface-3"),
        },
        line: v("line"),
        line2: v("line2"),
        fg: v("fg"),
        muted: v("muted"),
        faint: v("faint"),
        accent: {
          DEFAULT: v("accent"),
          hover: v("accent-hover"),
          soft: v("accent-soft"),
        },
        ok: v("ok"),
        warn: v("warn"),
        err: v("err"),
        info: v("info"),
        sel: v("sel"),
        axis: { x: "#ff5c5c", y: "#3fd07a", z: "#4c8dff" },
      },
      fontFamily: {
        sans: ["Inter", "system-ui", "-apple-system", "Segoe UI", "Roboto", "sans-serif"],
        mono: ["ui-monospace", "Cascadia Code", "SF Mono", "Menlo", "Consolas", "monospace"],
      },
      borderRadius: { xl: "14px", "2xl": "18px", "3xl": "24px" },
      boxShadow: {
        card: "inset 0 1px 0 rgba(255,255,255,0.04), 0 10px 30px -12px rgba(0,0,0,0.6)",
        panel: "inset 0 1px 0 rgba(255,255,255,0.03), 0 24px 60px -30px rgba(0,0,0,0.8)",
        glow: "0 0 0 1px rgba(61,123,253,0.45), 0 0 26px -4px rgba(61,123,253,0.35)",
      },
      backgroundImage: {
        "surface-grad": "linear-gradient(180deg, rgba(255,255,255,0.03), rgba(255,255,255,0) 40%)",
        "hero-grad": "radial-gradient(120% 120% at 50% 0%, rgba(61,123,253,0.10), rgba(0,0,0,0) 55%)",
      },
      keyframes: {
        "fade-in": { from: { opacity: 0, transform: "translateY(6px)" }, to: { opacity: 1, transform: "none" } },
        shimmer: { "100%": { transform: "translateX(100%)" } },
        pulsedot: { "0%,100%": { opacity: 1 }, "50%": { opacity: 0.35 } },
      },
      animation: {
        "fade-in": "fade-in 0.35s cubic-bezier(0.2,0.7,0.2,1) both",
        pulsedot: "pulsedot 1.8s ease-in-out infinite",
      },
    },
  },
  plugins: [],
};
