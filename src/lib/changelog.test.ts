import { describe, expect, it } from "vitest";
import { CHANGELOG, compareVersions, notesSince } from "./changelog";

describe("changelog", () => {
  it("orders versions numerically", () => {
    expect(compareVersions("0.1.10", "0.1.9")).toBeGreaterThan(0);
    expect(compareVersions("0.1.8", "0.1.8")).toBe(0);
    expect(compareVersions("0.1.8", "0.2.0")).toBeLessThan(0);
  });

  it("shows the versions after the last one seen", () => {
    expect(notesSince("0.1.7", "0.1.9").map((e) => e.version)).toEqual(["0.1.9", "0.1.8"]);
    expect(notesSince("0.1.8", "0.1.9").map((e) => e.version)).toEqual(["0.1.9"]);
    expect(notesSince("0.1.9", "0.1.9")).toEqual([]);
    // Updated from a version that did not record what was seen: only the current notes.
    expect(notesSince(null, "0.1.9").map((e) => e.version)).toEqual(["0.1.9"]);
  });

  it("has every note in both languages", () => {
    for (const e of CHANGELOG) expect(e.notes.pl.length).toBe(e.notes.en.length);
  });
});
