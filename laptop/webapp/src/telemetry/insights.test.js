import { describe, it, expect } from "vitest";
import { onInsightsMessage, getStats, getPsd, getTestRun, getReportsVersion, requestOpenReport, getOpenReport, clearOpenReport } from "./insights.js";

describe("insights store", () => {
  it("stores stats per handle and PSD per handle", () => {
    expect(onInsightsMessage({ kind: "insights", stats: [{ handle: 3, rate_hz: 100, axes: [{ std: 0.1 }] }] })).toBe(true);
    expect(getStats(3).rate_hz).toBe(100);
    onInsightsMessage({ kind: "insights_psd", handle: 3, f: [1, 2], psd: [[1, 2]] });
    expect(getPsd(3).f).toEqual([1, 2]);
  });
  it("drops stats for sensors missing from the next message", () => {
    onInsightsMessage({ kind: "insights", stats: [] });
    expect(getStats(3)).toBeUndefined();
  });
  it("tracks the test run and clears it after done; done bumps the reports version", () => {
    const v0 = getReportsVersion();
    onInsightsMessage({ kind: "testrun", phase: "running", id: "r1", elapsed: 1, seconds: 30 });
    expect(getTestRun().phase).toBe("running");
    onInsightsMessage({ kind: "testrun", phase: "done", id: "r1" });
    expect(getTestRun().phase).toBe("done");
    expect(getReportsVersion()).toBe(v0 + 1);
    onInsightsMessage({ kind: "report_ready", id: "r2" });
    expect(getReportsVersion()).toBe(v0 + 2);
  });
  it("ignores unrelated messages; open-report requests round-trip", () => {
    expect(onInsightsMessage({ kind: "stats" })).toBe(false);
    requestOpenReport("r9");
    expect(getOpenReport()).toBe("r9");
    clearOpenReport();
    expect(getOpenReport()).toBeNull();
  });
});

import { getLastReportEvent } from "./insights.js";

describe("report events carry their recording id", () => {
  it("records ready and error events with the id they belong to", () => {
    onInsightsMessage({ kind: "report_ready", id: "recA" });
    expect(getLastReportEvent()).toMatchObject({ id: "recA", ok: true });
    onInsightsMessage({ kind: "report_error", id: "recB", message: "bad file" });
    expect(getLastReportEvent()).toMatchObject({ id: "recB", ok: false, message: "bad file" });
  });
  it("a finished test run is reported under its recording id", () => {
    onInsightsMessage({ kind: "testrun", phase: "done", id: "recC" });
    expect(getLastReportEvent()).toMatchObject({ id: "recC", ok: true });
  });
});

import { getFilterConfig, getFilterSuggestion, getFilterError, clearFilterFeedback } from "./insights.js";

describe("filter state", () => {
  it("tracks configs, suggestions and errors per key", () => {
    onInsightsMessage({ kind: "filters", configs: { "1:Acc": { lowpass: { hz: 5, order: 4 }, notches: [] } } });
    expect(getFilterConfig("1:Acc").lowpass.hz).toBe(5);
    onInsightsMessage({ kind: "filter_suggestion", key: "1:Acc", config: { lowpass: { hz: 9, order: 4 }, notches: [] } });
    expect(getFilterSuggestion("1:Acc").lowpass.hz).toBe(9);
    onInsightsMessage({ kind: "filter_error", key: "1:Acc", message: "nope" });
    expect(getFilterError("1:Acc")).toBe("nope");
    clearFilterFeedback("1:Acc");
    expect(getFilterError("1:Acc")).toBeUndefined();
    expect(getFilterSuggestion("1:Acc")).toBeUndefined();
    onInsightsMessage({ kind: "filters", configs: {} });
    expect(getFilterConfig("1:Acc")).toBeUndefined();
  });
});
