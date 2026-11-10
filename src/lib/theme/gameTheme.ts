// The part of a launcher theme the Aerion mod draws in the game, with the CSS resolved here so
// the mod only reads colours.
import type { GameTheme, GameUiPrefs } from "../api";
import type { Theme } from "./schema";

const HEX = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3})\b/gi;

/** `lite`: the game's menus drop their particles (and the aurora with them), the costly part. */
export function toGameTheme(
  t: Theme,
  prefs: GameUiPrefs,
  lite = false,
  videoFrames: string | null = null,
): GameTheme {
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
    colors: prefs.colors,
    particles: prefs.particles && !lite ? b.particles : "none",
    logo: prefs.logo,
    animations: t.motion.enabled && t.motion.effects,
    // A picture (or GIF) background shows in the game's menus too; the launcher checks the file
    // and the mod reads it. A video shows there as frames made from it (lib/gameVideo); until they exist, the gradient.
    backgroundAsset: !prefs.colors
      ? null
      : b.type === "image" && b.asset
        ? b.asset
        : b.type === "video"
          ? videoFrames
          : null,
    backgroundDim: b.dim,
    backgroundFile: null,
  };
}
