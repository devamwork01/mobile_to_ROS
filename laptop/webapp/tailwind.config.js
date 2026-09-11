/** @type {import('tailwindcss').Config} */
// SensorStream Pro design tokens. Premium dark-first palette: charcoal/graphite
// surfaces (never pure black), off-white text, one restrained electric-blue
// accent, semantic status colors, and the universal X=red / Y=green / Z=blue
// axis colors used identically everywhere.
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        ink: "#0a0d12", // app background (deep charcoal, not black)
        surface: {
          DEFAULT: "#12161d",
          2: "#171c25",
          3: "#1f2530",
        },
        line: "#272e39",
        line2: "#333c49",
        fg: "#e8edf4",
        muted: "#8b95a4",
        faint: "#5a6472",
        accent: {
          DEFAULT: "#3d7bfd",
          hover: "#5a90ff",
          soft: "#17233d",
        },
        ok: "#3fd07a",
        warn: "#e3a635",
        err: "#ff5c5c",
        info: "#39c5cf",
        sel: "#e3b341",
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
