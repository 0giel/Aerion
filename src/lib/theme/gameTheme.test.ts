import { describe, expect, it } from "vitest";
import { toGameTheme } from "./gameTheme";
import type { Theme } from "./schema";

const files = import.meta.glob(["../../../themes/*.json", "!../../../themes/theme.schema.json"], {
  eager: true,
  import: "default",
}) as Record<string, Theme>;
const nocturne = Object.values(files).find((t) => t.id === "nocturne")!;

describe("game theme", () => {
  it("takes gradient stops from the CSS gradient", () => {
    const g = toGameTheme({
      ...nocturne,
      background: {
        ...nocturne.background,
        type: "gradient",
        gradient: "linear-gradient(160deg, #151826 0%, #0d0f14 100%)",
      },
    });
    expect(g.gradient).toEqual(["#151826", "#0d0f14"]);
  });

  it("falls back to the background colour for images", () => {
    const g = toGameTheme({ ...nocturne, background: { ...nocturne.background, type: "image" } });
    expect(g.gradient).toEqual([nocturne.colors.bg]);
  });
});
