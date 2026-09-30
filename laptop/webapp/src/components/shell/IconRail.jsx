import { Icons } from "../../icons.js";

const NAV = [
  ["Dashboard", "LayoutDashboard"],
  ["Recordings", "Database"],
  ["Diagnostics", "Activity"],
];
const THEME_NEXT = { system: "light", light: "dark", dark: "system" };
const THEME_ICON = { system: "Monitor", light: "Sun", dark: "Moon" };

function RailButton({ label, icon, on = false, onClick }) {
  const I = Icons[icon] || Icons.CircleDot;
  return (
    <button
      title={label}
      aria-label={label}
      onClick={onClick}
      className={`grid place-items-center w-9 h-9 rounded-xl transition-colors ${
        on ? "bg-accent-soft text-accent" : "text-muted hover:text-fg hover:bg-surface-2"
      }`}
    >
      <I size={18} />
    </button>
  );
}

export default function IconRail({ view, onView, theme, onTheme }) {
  return (
    <aside className="w-14 shrink-0 h-full flex flex-col items-center gap-1.5 py-3 bg-surface border-r border-line">
      <img src="./favicon.svg" alt="SensorStream" title="SensorStream" className="w-10 h-10 mb-3 rounded-xl shadow-glow" />
      {NAV.map(([label, icon]) => (
        <RailButton key={label} label={label} icon={icon} on={view === label} onClick={() => onView(label)} />
      ))}
      <div className="flex-1" />
      <RailButton label={`Theme: ${theme} (click to change)`} icon={THEME_ICON[theme]} onClick={() => onTheme(THEME_NEXT[theme])} />
      <RailButton label="Settings" icon="Settings" on={view === "Settings"} onClick={() => onView("Settings")} />
    </aside>
  );
}
