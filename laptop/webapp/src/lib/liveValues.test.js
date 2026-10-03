import { describe, it, expect } from "vitest";
import { liveText, slotChars } from "./liveValues.js";

describe("live values", () => {
  it("formats with fixed decimals and a dash for missing", () => {
    expect(liveText(-0.004, 2)).toBe("-0.00");
    expect(liveText(9.8123, 2)).toBe("9.81");
    expect(liveText(null, 2)).toBe("—");
  });

  it("slot width only grows, so a sign flip never resizes the header (flicker)", () => {
    let w = slotChars(0, ["0.00", "0.35", "-0.03"]);
    expect(w).toBe(5);
    w = slotChars(w, ["0.00", "0.00", "0.00"]);     // narrower values: the slot keeps its width
    expect(w).toBe(5);
    w = slotChars(w, ["-37.09", "8.68", "15.28"]);
    expect(w).toBe(6);
  });
});
