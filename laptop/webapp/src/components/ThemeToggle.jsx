import { Icons } from "../icons.js";

// Segmented System / Light / Dark control. Controlled: parent owns the value and persists it.
const OPTS = [
  { key: "system", label: "System", icon: "Settings" },
  { key: "light", label: "Light", icon: "Sun" },
  { key: "dark", label: "Dark", icon: "Moon" },
];

export default function ThemeToggle({ value, onChange, compact = false }) {
  return (
    <div className="inline-flex rounded-xl border border-line bg-surface-2 p-1 gap-1" role="radiogroup" aria-label="Theme">
      {OPTS.map((o) => {
        const active = value === o.key;
        const Icon = Icons[o.icon];
        return (
          <button
            key={o.key}
            role="radio"
            aria-checked={active}
            title={o.label}
            onClick={() => onChange(o.key)}
            className={
              "inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium transition-colors " +
              (active ? "bg-accent text-white shadow-glow" : "text-muted hover:text-fg")
            }
          >
            {Icon ? <Icon className="w-4 h-4" /> : null}
            {!compact && <span>{o.label}</span>}
          </button>
        );
      })}
    </div>
  );
}
