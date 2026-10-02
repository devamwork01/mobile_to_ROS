import { describe, it, expect } from "vitest";
import { openFilterPane, closeFilterPane, setFilterPaneTab, getFilterPane } from "./filterPane.js";

const acc = { key: "1:Acc", handle: 0, label: "Acceleration", kind: "vector", type: 1 };
const gyr = { key: "4:Gyro", handle: 2, label: "Angular Velocity", kind: "vector", type: 4 };

describe("filter pane store", () => {
  it("opens for a sensor, switches to another, closes", () => {
    closeFilterPane();
    expect(getFilterPane()).toBe(null);
    openFilterPane(acc);
    expect(getFilterPane()).toEqual({ row: acc, tab: null });
    setFilterPaneTab(2);
    openFilterPane(acc); // same sensor again keeps the tab
    expect(getFilterPane().tab).toBe(2);
    openFilterPane(gyr); // another panel switches the pane
    expect(getFilterPane()).toEqual({ row: gyr, tab: null });
    closeFilterPane();
    expect(getFilterPane()).toBe(null);
  });

  it("keeps the row it was opened with (the sensor may disappear from the grid)", () => {
    openFilterPane({ ...acc });
    const r = getFilterPane().row;
    expect(r.key).toBe("1:Acc");
    setFilterPaneTab(null);
    expect(getFilterPane().row).toBe(r);
    closeFilterPane();
    setFilterPaneTab(1); // no pane: ignored
    expect(getFilterPane()).toBe(null);
  });
});
