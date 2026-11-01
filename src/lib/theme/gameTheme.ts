// The part of a launcher theme the Aerion mod draws in the game, with the CSS resolved here so
// the mod only reads colours.
import type { GameTheme } from "../api";
import type { Theme } from "./schema";

const HEX = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})\b/gi;

export function toGameTheme(t: Theme): GameTheme {
  const c = t.colors;
  const b = t.background;
  const gradient =
    b.type === "gradient" && b.gradient
      ? (b.gradient.match(HEX) ?? [])
      : [b.type === "color" ? b.color : c.bg];
  return {
    name: t.name,
    base: t.base,
    bg: c.bg,
    surface: c.surface,
    surface2: c.surface2,
    border: c.border,
    text: c.text,
    textMuted: c.textMuted,
    accent: c.accent,
    accentText: c.accentText,
    gradient: gradient.length ? gradient : [c.bg],
    radius: Math.round(t.radius.md),
  };
}
