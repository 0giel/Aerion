import { afterEach, describe, expect, it, vi } from "vitest";
import { normalizeTheme, themeToCss, type Theme } from "./schema";

const files = import.meta.glob(["../../../themes/*.json", "!../../../themes/theme.schema.json"], {
  eager: true,
  import: "default",
}) as Record<string, Record<string, unknown>>;
const nocturne = Object.values(files).find((t) => t.id === "nocturne") as unknown as Theme;

const withoutSchema = (raw: Record<string, unknown>) => {
  const { $schema: _schema, ...rest } = raw;
  return rest;
};

describe("theme normalisation", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("keeps every built-in theme unchanged", () => {
    expect(Object.keys(files)).toHaveLength(7);
    for (const raw of Object.values(files)) {
      expect(normalizeTheme(raw, nocturne), String(raw.id)).toEqual(withoutSchema(raw));
    }
  });

  it("fills missing fields from the fallback", () => {
    const t = normalizeTheme({ name: "Mini", base: "light", colors: { accent: "#ff0000" } }, nocturne);
    expect(t.colors.accent).toBe("#ff0000");
    expect(t.colors.bg).toBe(nocturne.colors.bg);
    expect(t.radius).toEqual(nocturne.radius);
    expect(t.base).toBe("light");
  });

  it("clamps numbers and rejects unknown enum values", () => {
    const t = normalizeTheme(
      {
        radius: { sm: 999, md: -4 },
        font: { size: 2 },
        layout: { scale: 9, sidebar: "bottom" },
        motion: { speed: 0 },
        base: "purple",
        background: { particles: "fire", dim: 5 },
      },
      nocturne,
    );
    expect(t.radius.sm).toBe(24);
    expect(t.radius.md).toBe(0);
    expect(t.font.size).toBe(11);
    expect(t.layout.scale).toBe(1.5);
    expect(t.layout.sidebar).toBe(nocturne.layout.sidebar);
    expect(t.motion.speed).toBe(0.25);
    expect(t.base).toBe(nocturne.base);
    expect(t.background.particles).toBe(nocturne.background.particles);
    expect(t.background.dim).toBe(0.95);
  });

  it("never lets theme strings break out of CSS or load remote resources", () => {
    for (const bad of [
      "url(https://example.com/x.png)",
      "red; } body { display: none",
      "</style><script>alert(1)</script>",
    ]) {
      const t = normalizeTheme({ font: { family: bad }, background: { gradient: bad } }, nocturne);
      expect(t.font.family).toBe(nocturne.font.family);
      expect(t.background.gradient).toBe(nocturne.background.gradient);
    }
  });

  it("only accepts plain asset file names", () => {
    const asset = (a: unknown) => normalizeTheme({ background: { asset: a } }, nocturne).background.asset;
    expect(asset("bg-1.webp")).toBe("bg-1.webp");
    expect(asset("../../etc/passwd")).toBe(nocturne.background.asset);
    expect(asset("a/b.png")).toBe(nocturne.background.asset);
    expect(asset(null)).toBeNull();
  });

  it("validates colours with CSS.supports when available", () => {
    vi.stubGlobal("CSS", { supports: (_p: string, v: string) => /^#[0-9a-f]{3,8}$/i.test(v) });
    const t = normalizeTheme({ colors: { bg: "not-a-colour", text: "#abc" } }, nocturne);
    expect(t.colors.bg).toBe(nocturne.colors.bg);
    expect(t.colors.text).toBe("#abc");
  });
});

describe("design tokens", () => {
  it("maps the theme to CSS custom properties", () => {
    const css = themeToCss(nocturne, false);
    expect(css["--c-accent"]).toBe(nocturne.colors.accent);
    expect(css["--r-md"]).toBe(`${nocturne.radius.md}px`);
    expect(css["--dur"]).toBe("180ms");
  });

  it("turns animations off for reduced motion or a disabled theme setting", () => {
    expect(themeToCss(nocturne, true)["--dur"]).toBe("0ms");
    expect(themeToCss({ ...nocturne, motion: { enabled: false, speed: 1 } }, false)["--dur"]).toBe("0ms");
    expect(themeToCss({ ...nocturne, motion: { enabled: true, speed: 2 } }, false)["--dur"]).toBe("90ms");
  });
});
