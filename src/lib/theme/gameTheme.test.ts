import { describe, expect, it } from "vitest";
import { toGameTheme } from "./gameTheme";
import type { Theme } from "./schema";

const files = import.meta.glob("../../../themes/builtin/*.json", {
  eager: true,
  import: "default",
}) as Record<string, Theme>;
const nocturne = Object.values(files).find((t) => t.id === "nocturne")!;
const prefs = { colors: true, particles: true, logo: true };

describe("game theme", () => {
  it("takes gradient stops from the CSS gradient", () => {
    const g = toGameTheme(
      {
        ...nocturne,
        background: {
          ...nocturne.background,
          type: "gradient",
          gradient: "linear-gradient(160deg, #151826 0%, #0d0f14 100%)",
        },
      },
      prefs,
    );
    expect(g.gradient).toEqual(["#151826", "#0d0f14"]);
  });

  it("falls back to the background colour for images", () => {
    const g = toGameTheme({ ...nocturne, background: { ...nocturne.background, type: "image" } }, prefs);
    expect(g.gradient).toEqual([nocturne.colors.bg]);
  });

  it("passes the player's choices", () => {
    const g = toGameTheme(nocturne, { colors: false, particles: false, logo: true });
    expect([g.colors, g.particles, g.logo]).toEqual([false, "none", true]);
    expect(toGameTheme(nocturne, prefs).particles).toBe(nocturne.background.particles);
    // Lite mode takes the particles (and the aurora) out of the game's menus.
    expect(toGameTheme(nocturne, prefs, true).particles).toBe("none");
  });
});
