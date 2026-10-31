import { describe, expect, it } from "vitest";
import { instanceTitle } from "./api";

describe("instanceTitle", () => {
  const inst = { name: "26.3", gameVersion: "26.3", loader: "vanilla", loaderVersion: null } as const;

  it("does not repeat the version when the name is the version", () => {
    expect(instanceTitle(inst)).toBe("26.3");
  });

  it("shows name and version otherwise", () => {
    expect(instanceTitle({ ...inst, name: "Survival" })).toBe("Survival · 26.3");
  });
});
