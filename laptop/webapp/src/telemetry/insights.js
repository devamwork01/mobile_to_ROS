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
let filterConfigs = {}; // key -> config (server truth, broadcast to every dashboard)
const filterSuggestions = new Map(); // key -> suggested config
const filterSuggestionAxes = new Map(); // key -> [sX, sY, sZ] per-axis suggestions (3-axis sensors)
const filterErrors = new Map(); // key -> message
const filterErrorRunning = new Map(); // key -> the previous filter is still running despite the error
const filterReports = new Map(); // key -> latest per-axis spectrum findings (server filter_report)
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
      psd.set(m.handle, { f: m.f, psd: m.psd, psd_f: m.psd_f }); // psd_f: "after" curves while a filter is active
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
    case "filters":
      filterConfigs = m.configs || {};
      break;
    case "filter_suggestion":
      filterSuggestions.set(m.key, m.config);
      filterSuggestionAxes.set(m.key, m.axes || null);
      filterErrors.delete(m.key);
      break;
    case "filter_error":
      filterErrors.set(m.key, m.message);
      filterErrorRunning.set(m.key, !!m.running);
      break;
    case "filter_report":
      filterReports.set(m.key, m.report);
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
export const getFilterConfig = (key) => filterConfigs[key];
export const getFilterSuggestion = (key) => filterSuggestions.get(key);
export const getFilterError = (key) => filterErrors.get(key);
export const getFilterErrorRunning = (key) => filterErrorRunning.get(key) === true;
export const getFilterReport = (key) => filterReports.get(key);
export function clearFilterSuggestion(key) {
  filterSuggestions.delete(key);
  filterSuggestionAxes.delete(key);
  emit();
}
export function clearFilterError(key) {
  filterErrors.delete(key);
  emit();
}
export function clearFilterFeedback(key) {
  filterSuggestions.delete(key);
  filterSuggestionAxes.delete(key);
  filterErrors.delete(key);
  emit();
}
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
export const useFilterConfig = (key) => useSyncExternalStore(subscribe, () => filterConfigs[key], () => filterConfigs[key]);
export const useFilterSuggestionAxes = (key) => useSyncExternalStore(subscribe, () => filterSuggestionAxes.get(key), () => filterSuggestionAxes.get(key));
export const useFilterSuggestion = (key) => useSyncExternalStore(subscribe, () => filterSuggestions.get(key), () => filterSuggestions.get(key));
export const useFilterErrorRunning = (key) => useSyncExternalStore(subscribe, () => filterErrorRunning.get(key) === true, () => filterErrorRunning.get(key) === true);
export const useFilterReport = (key) => useSyncExternalStore(subscribe, () => filterReports.get(key), () => filterReports.get(key));
export const useFilterError = (key) => useSyncExternalStore(subscribe, () => filterErrors.get(key), () => filterErrors.get(key));
export const useLastReportEvent = () => useSyncExternalStore(subscribe, getLastReportEvent, getLastReportEvent);
export const useOpenReportRequest = () => useSyncExternalStore(subscribe, getOpenReport, getOpenReport);
