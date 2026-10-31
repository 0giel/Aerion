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

  it("adds the loader unless the name already mentions it", () => {
    const forge = {
      name: "Forge 1.20.1",
      gameVersion: "1.20.1",
      loader: "forge",
      loaderVersion: "47.4.28",
    } as const;
    expect(instanceTitle(forge)).toBe("Forge 1.20.1");
    expect(instanceTitle({ ...forge, name: "Modded" })).toBe("Modded · 1.20.1 · Forge");
  });
});
