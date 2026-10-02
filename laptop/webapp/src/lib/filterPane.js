// Which sensor's filter the side pane is editing (one pane at a time), and the per-axis tab open in
// it (null when not per axis) so that sensor's spectrum can mark the matching suggestion.
import { useSyncExternalStore } from "react";

let pane = null; // { row, tab } | null
const listeners = new Set();
const emit = () => listeners.forEach((l) => l());
const subscribe = (cb) => {
  listeners.add(cb);
  return () => listeners.delete(cb);
};

export function openFilterPane(row) {
  pane = { row, tab: pane && pane.row.key === row.key ? pane.tab : null };
  emit();
}
export function closeFilterPane() {
  if (pane) {
    pane = null;
    emit();
  }
}
export function setFilterPaneTab(tab) {
  if (pane && pane.tab !== tab) {
    pane = { ...pane, tab };
    emit();
  }
}
export const getFilterPane = () => pane;
export const useFilterPane = () => useSyncExternalStore(subscribe, getFilterPane, getFilterPane);
