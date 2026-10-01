// Insights state fed by the telemetry WebSocket (server-computed, full-rate): live per-sensor
// stats (~1 Hz), on-demand spectra, and the current test run. Pure module + React hooks.
import { useSyncExternalStore } from "react";

let stats = new Map(); // handle -> {handle,type,rate_hz,jitter_ms,gaps,axes:[{mean,std,p2p}]}
const psd = new Map(); // handle -> {f, psd:[[...]...]}
let testRun = null;
let reportsVersion = 0;
let lastReportEvent = null; // {id, ok, message, n}: which recording a report finished/failed for
let eventSeq = 0;
const reportEvent = (id, ok, message) => { lastReportEvent = { id, ok, message, n: ++eventSeq }; };
let openReport = null;
const listeners = new Set();
const emit = () => listeners.forEach((l) => l());
const subscribe = (cb) => {
  listeners.add(cb);
  return () => listeners.delete(cb);
};

export function onInsightsMessage(m) {
  switch (m.kind) {
    case "insights":
      stats = new Map((m.stats || []).map((s) => [s.handle, s]));
      break;
    case "insights_psd":
      psd.set(m.handle, { f: m.f, psd: m.psd });
      break;
    case "testrun":
      testRun = m;
      if (m.phase === "done") {
        reportsVersion += 1;
        reportEvent(m.id, true);
      }
      break;
    case "report_ready":
      reportsVersion += 1;
      reportEvent(m.id, true);
      break;
    case "report_error":
      reportEvent(m.id, false, m.message);
      break;
    default:
      return false;
  }
  emit();
  return true;
}

export const getStats = (h) => stats.get(h);
export const getPsd = (h) => psd.get(h);
export const getTestRun = () => testRun;
export const getReportsVersion = () => reportsVersion;
export const getOpenReport = () => openReport;
export const getLastReportEvent = () => lastReportEvent;
export function requestOpenReport(id) {
  openReport = id;
  emit();
}
export function clearOpenReport() {
  openReport = null;
  emit();
}
export function dismissTestRun() {
  testRun = null;
  emit();
}

export const useInsightStats = (h) => useSyncExternalStore(subscribe, () => stats.get(h), () => stats.get(h));
export const usePsd = (h) => useSyncExternalStore(subscribe, () => psd.get(h), () => psd.get(h));
export const useTestRun = () => useSyncExternalStore(subscribe, getTestRun, getTestRun);
export const useReportsVersion = () => useSyncExternalStore(subscribe, getReportsVersion, getReportsVersion);
export const useLastReportEvent = () => useSyncExternalStore(subscribe, getLastReportEvent, getLastReportEvent);
export const useOpenReportRequest = () => useSyncExternalStore(subscribe, getOpenReport, getOpenReport);
