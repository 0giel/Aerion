import { describe, expect, it } from "vitest";
import { decodeInstance, encodeInstance } from "./instanceCode";

const inst = {
  name: "Z kolegą",
  gameVersion: "26.3",
  loader: "fabric",
  loaderVersion: "0.20.1",
  aerionMod: true,
  maxMemoryMb: 4096,
} as never;

const entry = (over: Record<string, unknown>) =>
  ({
    fileName: "a.jar",
    kind: "mod",
    enabled: true,
    provider: "modrinth",
    projectId: "AANobbMI",
    versionId: "v1",
    title: "Sodium",
    ...over,
  }) as never;

describe("instance codes", () => {
  it("carry the game, loader, memory and the content with ids", async () => {
    const { code, left } = await encodeInstance(inst, [
      entry({}),
      entry({ kind: "shader", provider: "curseforge", projectId: "123", versionId: "456", title: "Shady" }),
      entry({ provider: null, projectId: null, versionId: null, title: "Mine" }),
      entry({ enabled: false, title: "Off" }),
    ]);
    expect(code.startsWith("aerionI1:")).toBe(true);
    expect(left).toBe(1);
    const back = await decodeInstance(code);
    expect(back).toMatchObject({
      name: "Z kolegą",
      gameVersion: "26.3",
      loader: "fabric",
      loaderVersion: "0.20.1",
      aerionMod: true,
      maxMemoryMb: 4096,
    });
    expect(back.items).toEqual([
      { provider: "modrinth", projectId: "AANobbMI", versionId: "v1", kind: "mod", title: "Sodium" },
      { provider: "curseforge", projectId: "123", versionId: "456", kind: "shader", title: "Shady" },
    ]);
  });

  it("refuse other codes and damaged ones", async () => {
    await expect(decodeInstance("aerion1:abc")).rejects.toThrow("not-an-instance-code");
    await expect(decodeInstance("aerionI1:@@@")).rejects.toThrow("damaged");
  });
});
