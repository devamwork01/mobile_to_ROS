import { describe, it, expect } from "vitest";
import { isCurrentPhone } from "./phoneSession.js";

describe("phone session", () => {
  it("a disconnect for the current phone ends it", () => {
    expect(isCurrentPhone(7, 7)).toBe(true);
  });

  it("a late disconnect of the previous session is ignored", () => {
    // After a Wi-Fi blip the phone reconnects (new id 8) before the server notices the old
    // socket (id 7) is dead, so phone_disconnected(7) arrives after phone_connected(8).
    expect(isCurrentPhone(8, 7)).toBe(false);
  });

  it("without ids to compare (older server, no phone yet) the disconnect applies", () => {
    expect(isCurrentPhone(null, 7)).toBe(true);
    expect(isCurrentPhone(8, undefined)).toBe(true);
  });
});
