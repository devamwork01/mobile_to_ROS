import { useRef } from "react";
import { Icons } from "../../icons.js";
import { useSignal } from "../../telemetry/store.js";
import { AXIS } from "../../telemetry/signals.js";
import { liveText, slotChars } from "../../lib/liveValues.js";

const COLORS = [AXIS.X, AXIS.Y, AXIS.Z, "#b57edc"];

export function IconBtn({ title, icon, onClick, active = false }) {
  const I = Icons[icon] || Icons.CircleDot;
  return (
    <button
      title={title}
      aria-label={title}
      onClick={onClick}
      className={`grid place-items-center w-7 h-7 rounded-lg transition-colors ${
        active ? "text-accent bg-accent-soft" : "text-muted hover:text-fg hover:bg-surface-3"
      }`}
    >
      <I size={15} />
    </button>
  );
}

export function Segmented({ options, value, onChange }) {
  return (
    <div className="flex gap-0.5 p-0.5 rounded-lg bg-surface-2 border border-line">
      {options.map(([v, label]) => (
        <button
          key={String(v)}
          onClick={() => onChange(v)}
          className={`num text-[11px] px-2 py-0.5 rounded-md ${v === value ? "bg-surface-3 text-fg" : "text-muted hover:text-fg"}`}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

// Latest X/Y/Z (or qx..qw) in the header, re-rendered at the store's ~10 Hz card rate.
export function LiveValues({ handle, kind }) {
  const rec = useSignal(handle);
  const width = useRef(0); // grow-only slot width (ch): values changing sign/digits never resize the header
  if (kind === "scalar") return null;
  const v = rec?.v || [];
  const n = kind === "orientation" ? 4 : 3;
  const d = kind === "orientation" ? 3 : 2;
  const texts = Array.from({ length: n }, (_, i) => liveText(v[i], d));
  width.current = slotChars(width.current, texts);
  return (
    <span className="num text-[11px] flex gap-2 ml-1 whitespace-nowrap">
      {texts.map((t, i) => (
        <span key={i} className="inline-block text-right" style={{ color: COLORS[i], minWidth: `${width.current}ch` }}>
          {t}
        </span>
      ))}
    </span>
  );
}

export function StatusBadge({ state }) {
  if (state === "live") return null;
  return (
    <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-warn/15 text-warn whitespace-nowrap">
      {state === "stale" ? "stale" : "not streaming"}
    </span>
  );
}

export function NotStreaming() {
  return (
    <div className="h-full min-h-[120px] grid place-items-center text-center text-xs text-faint p-4">
      Not streaming. Turn this sensor on in the phone app, or unpin it.
    </div>
  );
}

export default function Panel({
  title, unit, live, badge, tools, collapsed = false, maximized = false,
  onCollapse, onMaximize, onClose, className = "", bodyClassName = "", footer = null, children,
}) {
  return (
    <section className={`panel flex flex-col min-w-0 overflow-hidden ${className}`}>
      {/* Narrow panels (e.g. beside the filter pane) wrap the tools onto a second line rather than
          squeezing the title away. */}
      <header className={`flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-1.5 min-h-[40px] ${collapsed ? "" : "border-b border-line"}`}>
        <h2 className="text-[11px] font-semibold uppercase tracking-[0.08em] text-muted truncate min-w-[5.5rem]">{title}</h2>
        {unit && <span className="text-[11px] text-faint whitespace-nowrap">{unit}</span>}
        {live}
        {badge}
        <div className="ml-auto flex items-center gap-1">
          {tools}
          {onMaximize && (
            <IconBtn title={maximized ? "Restore (Esc)" : "Maximise"} icon={maximized ? "Minimize2" : "Maximize2"} onClick={onMaximize} />
          )}
          {onCollapse && !maximized && (
            <IconBtn title={collapsed ? "Expand" : "Collapse"} icon={collapsed ? "ChevronRight" : "ChevronDown"} onClick={onCollapse} />
          )}
          {onClose && <IconBtn title="Unpin" icon="X" onClick={onClose} />}
        </div>
      </header>
      {!collapsed && <div className={`relative flex-auto min-h-0 ${bodyClassName}`}>{children}</div>}
      {!collapsed && footer}
    </section>
  );
}
