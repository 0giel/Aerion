// Theme format: one JSON file of design tokens (see docs/THEMING.md). Every value is
// validated and clamped, so a broken or hand-edited theme can never break the UI.

export type Particles = "none" | "sparkles" | "dust" | "snow";
export type BackgroundType = "color" | "gradient" | "image" | "video";

export interface ThemeColors {
  bg: string;
  surface: string;
  surface2: string;
  surface3: string;
  border: string;
  text: string;
  textMuted: string;
  textFaint: string;
  accent: string;
  accentText: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
  focus: string;
}

export interface Theme {
  id: string;
  name: string;
  author?: string;
  base: "dark" | "light";
  colors: ThemeColors;
  radius: { sm: number; md: number; lg: number; xl: number };
  font: { family: string; mono: string; size: number };
  density: "comfortable" | "compact";
  layout: { sidebar: "left" | "right" | "top" | "bottom"; scale: number };
  effects: { blur: number; glass: number; shadow: number };
  motion: { enabled: boolean; speed: number };
  background: {
    type: BackgroundType;
    color: string;
    gradient: string | null;
    asset: string | null;
    dim: number;
    blur: number;
    ribbons: boolean;
    particles: Particles;
  };
}

export const COLOR_KEYS: (keyof ThemeColors)[] = [
  "bg",
  "surface",
  "surface2",
  "surface3",
  "border",
  "text",
  "textMuted",
  "textFaint",
  "accent",
  "accentText",
  "success",
  "warning",
  "danger",
  "info",
  "focus",
];

export const FONT_STACKS: Record<string, string> = {
  system:
    '"Inter", "Segoe UI Variable Text", "Segoe UI", system-ui, -apple-system, "Cantarell", "Noto Sans", "Ubuntu", sans-serif',
  "system-mono":
    '"JetBrains Mono", "Cascadia Code", "SF Mono", "Consolas", "Ubuntu Mono", "Noto Sans Mono", monospace',
  serif: '"Iowan Old Style", "Palatino Linotype", "Georgia", serif',
  rounded: '"Nunito", "Quicksand", "Varela Round", system-ui, sans-serif',
};

const clamp = (v: unknown, min: number, max: number, d: number) =>
  typeof v === "number" && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : d;

const isColor = (v: unknown): v is string =>
  typeof v === "string" && v.length < 64 && (typeof CSS === "undefined" || CSS.supports("color", v));

/** Values that end up inside CSS must not be able to break out of a declaration. */
const safeCss = (v: unknown): v is string =>
  typeof v === "string" && v.length < 2000 && !/[;{}]|<\/|url\s*\(/i.test(v);

function obj(v: unknown): Record<string, unknown> {
  return v && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
}

export function normalizeTheme(raw: unknown, fallback: Theme): Theme {
  const r = obj(raw);
  const c = obj(r.colors);
  const colors = { ...fallback.colors };
  for (const k of COLOR_KEYS) if (isColor(c[k])) colors[k] = c[k] as string;
  const radius = obj(r.radius);
  const font = obj(r.font);
  const layout = obj(r.layout);
  const effects = obj(r.effects);
  const motion = obj(r.motion);
  const bg = obj(r.background);
  const fb = fallback.background;
  const bgType = ["color", "gradient", "image", "video"].includes(bg.type as string)
    ? (bg.type as BackgroundType)
    : fb.type;
  return {
    id: typeof r.id === "string" ? r.id : fallback.id,
    name: typeof r.name === "string" && r.name.trim() ? r.name.slice(0, 64) : fallback.name,
    author: typeof r.author === "string" ? r.author.slice(0, 64) : undefined,
    base: r.base === "light" ? "light" : r.base === "dark" ? "dark" : fallback.base,
    colors,
    radius: {
      sm: clamp(radius.sm, 0, 24, fallback.radius.sm),
      md: clamp(radius.md, 0, 32, fallback.radius.md),
      lg: clamp(radius.lg, 0, 40, fallback.radius.lg),
      xl: clamp(radius.xl, 0, 48, fallback.radius.xl),
    },
    font: {
      family: typeof font.family === "string" && safeCss(font.family) ? font.family : fallback.font.family,
      mono: typeof font.mono === "string" && safeCss(font.mono) ? font.mono : fallback.font.mono,
      size: clamp(font.size, 11, 20, fallback.font.size),
    },
    density:
      r.density === "compact" ? "compact" : r.density === "comfortable" ? "comfortable" : fallback.density,
    layout: {
      sidebar: ["left", "right", "top", "bottom"].includes(layout.sidebar as string)
        ? (layout.sidebar as Theme["layout"]["sidebar"])
        : fallback.layout.sidebar,
      scale: clamp(layout.scale, 0.75, 1.5, fallback.layout.scale),
    },
    effects: {
      blur: clamp(effects.blur, 0, 40, fallback.effects.blur),
      glass: clamp(effects.glass, 0.2, 1, fallback.effects.glass),
      shadow: clamp(effects.shadow, 0, 1, fallback.effects.shadow),
    },
    motion: {
      enabled: typeof motion.enabled === "boolean" ? motion.enabled : fallback.motion.enabled,
      speed: clamp(motion.speed, 0.25, 3, fallback.motion.speed),
    },
    background: {
      type: bgType,
      color: isColor(bg.color) ? bg.color : fb.color,
      gradient: safeCss(bg.gradient) ? bg.gradient : bg.gradient === null ? null : fb.gradient,
      asset:
        typeof bg.asset === "string" && /^[\w.-]{1,128}$/.test(bg.asset)
          ? bg.asset
          : bg.asset === null
            ? null
            : fb.asset,
      dim: clamp(bg.dim, 0, 0.95, fb.dim),
      blur: clamp(bg.blur, 0, 40, fb.blur),
      ribbons: typeof bg.ribbons === "boolean" ? bg.ribbons : fb.ribbons,
      particles: ["none", "sparkles", "dust", "snow"].includes(bg.particles as string)
        ? (bg.particles as Particles)
        : fb.particles,
    },
  };
}

/** Design tokens as CSS custom properties. */
export function themeToCss(t: Theme, reducedMotion: boolean): Record<string, string> {
  const c = t.colors;
  const motion = t.motion.enabled && !reducedMotion;
  const vars: Record<string, string> = {
    "--c-bg": c.bg,
    "--c-surface": c.surface,
    "--c-surface-2": c.surface2,
    "--c-surface-3": c.surface3,
    "--c-border": c.border,
    "--c-text": c.text,
    "--c-text-muted": c.textMuted,
    "--c-text-faint": c.textFaint,
    "--c-accent": c.accent,
    "--c-accent-text": c.accentText,
    "--c-success": c.success,
    "--c-warning": c.warning,
    "--c-danger": c.danger,
    "--c-info": c.info,
    "--c-focus": c.focus,
    "--r-sm": `${t.radius.sm}px`,
    "--r-md": `${t.radius.md}px`,
    "--r-lg": `${t.radius.lg}px`,
    "--r-xl": `${t.radius.xl}px`,
    "--font": FONT_STACKS[t.font.family] ?? `${t.font.family}, ${FONT_STACKS.system}`,
    "--font-mono": FONT_STACKS[t.font.mono] ?? `${t.font.mono}, ${FONT_STACKS["system-mono"]}`,
    "--fs": `${t.font.size}px`,
    "--scale": String(t.layout.scale),
    "--blur": `${t.effects.blur}px`,
    "--glass": String(t.effects.glass),
    "--shadow-a": String(t.effects.shadow),
    "--dur": motion ? `${Math.round(180 / t.motion.speed)}ms` : "0ms",
    "--dur-slow": motion ? `${Math.round(360 / t.motion.speed)}ms` : "0ms",
    "--bg-dim": String(t.background.dim),
    "--bg-blur": `${t.background.blur}px`,
  };
  return vars;
}
