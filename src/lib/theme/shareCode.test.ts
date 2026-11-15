import { describe, expect, it } from "vitest";
import { decodeTheme, encodeTheme } from "./shareCode";

const theme = {
  $schema: "./theme.schema.json",
  id: "sunset",
  name: "Sunset",
  css: "body{background:url(https://example.com/x.png)}",
  colors: { bg: "#101010", accent: "#ff8800" },
  background: { type: "video", asset: "clip.mp4", gradient: "linear-gradient(#000, #111)" },
};

describe("theme share code", () => {
  it("round-trips the colours and drops CSS and background files", async () => {
    const code = await encodeTheme(theme);
    expect(code.startsWith("aerion1:")).toBe(true);
    const back = await decodeTheme(code);
    expect(back.name).toBe("Sunset");
    expect(back.colors).toEqual(theme.colors);
    expect(back.css).toBeUndefined();
    expect(back.$schema).toBeUndefined();
    expect(back.background).toEqual({ type: "gradient", asset: null, gradient: theme.background.gradient });
  });

  it("rejects other text and damaged codes", async () => {
    await expect(decodeTheme("hello")).rejects.toThrow("not-a-theme-code");
    await expect(decodeTheme("aerion1:@@@@")).rejects.toThrow("damaged");
  });
});
