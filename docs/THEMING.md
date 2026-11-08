# Theming Aerion

A theme is **one JSON file** of design tokens, optionally with a CSS file next to it. Every
component reads only these tokens (CSS custom properties), so a theme can restyle the whole
launcher without touching code. Changes apply live — no restart.

- Built-in themes: Nocturne (default), Dark, Light, AMOLED, Emerald, Rose, Amber
  (`themes/*.json` in the repository, embedded at build time).
- Your themes: `<data>/themes/<id>.json` (+ `<id>.css`), images/videos in `<data>/themes/assets/`.
  `<data>` is shown in **Settings → Data & storage** (Linux `~/.local/share/aerion`,
  Windows `%LOCALAPPDATA%\Aerion`, macOS `~/Library/Application Support/Aerion`).
- Schema: `themes/theme.schema.json`. The launcher also keeps a copy in `<data>/themes/`, so
  editors that understand JSON Schema (VS Code, JetBrains…) autocomplete and validate the file
  when it starts with `"$schema": "./theme.schema.json"`.

## Three ways to make a theme

1. **Theme editor** — Settings → Appearance → *Theme editor*. Every change previews immediately;
   *Save theme* writes `<data>/themes/<id>.json` and selects it. Built-in themes are never
   overwritten: editing one creates a copy ("Nocturne ✦").
2. **By hand** — copy a built-in theme into `<data>/themes/`, give it a new `id`/`name`, edit.
   The folder is watched: saving the file in any editor updates the open launcher instantly.
3. **Import** — Settings → Appearance → *Import theme…* accepts a plain theme JSON or an
   exported bundle (below).

## Format

```jsonc
{
  "$schema": "./theme.schema.json",
  "id": "midnight",            // [a-z0-9_-], ≤ 48 chars; set by the launcher on save/import
  "name": "Midnight",
  "author": "you",
  "base": "dark",              // "dark" | "light": native controls + "follow system" pairing
  "colors": {
    "bg": "#101218", "surface": "#181b22", "surface2": "#20242c", "surface3": "#2a2f39",
    "border": "#ffffff14",
    "text": "#e9ecf2", "textMuted": "#a3a9b6", "textFaint": "#707684",
    "accent": "#7aa2ff", "accentText": "#0b1020",
    "success": "#4ade80", "warning": "#fbbf24", "danger": "#f87171", "info": "#60a5fa",
    "focus": "#a9c1ff"
  },
  "radius": { "sm": 6, "md": 10, "lg": 16, "xl": 22 },          // px
  "font": { "family": "system", "mono": "system-mono", "size": 14 },
  "density": "comfortable",                                     // or "compact"
  "layout": { "sidebar": "left", "scale": 1,                    // "left" | "right" | "top" | "bottom", 0.75–1.5
             "navMode": "fixed", "navIconSize": 19,           // "fixed" | "hover" | "icons", 15–26
             "navGaps": ["home"] },                           // menu items with a divider after them
  "effects": { "blur": 18, "glass": 0.72, "shadow": 0.45 },
  "motion": { "enabled": true, "speed": 1, "effects": true },  // effects: springy clicks, page transitions, tilting cards, sparks
  "background": {
    "type": "gradient",          // "color" | "gradient" | "image" | "video"
    "color": "#101218",
    "gradient": "linear-gradient(160deg, #151826 0%, #0d0f14 100%)",
    "asset": null,               // file name in <data>/themes/assets for image/video
    "dim": 0,                    // 0–0.95 dark overlay over image/video
    "blur": 0,                   // px
    "ribbons": true,             // soft light ribbons
    "particles": "sparkles"      // "none" | "sparkles" | "dust" | "snow"
  }
}
```

Only `name`, `base` and `colors` are needed; everything missing is taken from Nocturne.

### Validation — a theme can't break the launcher
- Colours must be valid CSS colours (hex with alpha, `rgb()`, `hsl()`, `oklch()`…); invalid ones
  fall back to the default theme's value.
- Numbers are clamped to the ranges above.
- Strings that end up in CSS (`font.family`, `background.gradient`) may not contain `;`, `{`, `}`,
  `</` or `url(` — remote resources are impossible by design (the CSP forbids them too).
- Background assets are plain file names (`^[A-Za-z0-9_.-]{1,128}$`) served from the assets folder
  only; JSON and CSS files are limited to 512 KB each.

### Fonts
`font.family` is a preset — `system`, `serif`, `rounded` — or any CSS `font-family` list such as
`"Atkinson Hyperlegible", sans-serif`; the system stack is always appended as a fallback.
`font.mono` (`system-mono` or a list) styles logs and code. Fonts must be installed on the system.

## CSS tokens

| Token | From | Used for |
|-------|------|----------|
| `--c-bg`, `--c-surface`, `--c-surface-2`, `--c-surface-3` | `colors.bg/surface/surface2/surface3` | backgrounds by elevation |
| `--c-border` | `colors.border` | hairlines, card borders |
| `--c-text`, `--c-text-muted`, `--c-text-faint` | `colors.text*` | text hierarchy |
| `--c-accent`, `--c-accent-text` | `colors.accent*` | primary actions, selection, progress |
| `--c-success`, `--c-warning`, `--c-danger`, `--c-info` | `colors.*` | statuses, toasts, badges |
| `--c-focus` | `colors.focus` | keyboard focus ring |
| `--r-sm`, `--r-md`, `--r-lg`, `--r-xl` | `radius.*` | corner radii |
| `--font`, `--font-mono`, `--fs` | `font.*` | typography |
| `--scale` | `layout.scale` | UI zoom |
| `--blur`, `--glass`, `--shadow-a` | `effects.*` | glass surfaces, shadows |
| `--dur`, `--dur-slow` | `motion.*` (0 ms when disabled) | transitions |
| `--bg-dim`, `--bg-blur` | `background.dim/blur` | image/video backgrounds |

Layout switches are data attributes on `<html>`: `data-base` (`dark`/`light`), `data-density`,
`data-sidebar` (`left`/`right`/`top`/`bottom`), `data-motion` (`on`/`off`).

## Custom CSS

`<id>.css` next to the theme is loaded after the built-in styles while the theme is active. Use
the tokens and data attributes rather than hard-coded values so it keeps working in previews:

```css
/* midnight.css — bolder headings, square buttons in compact mode */
h1, h2 { letter-spacing: -0.02em; }
[data-density="compact"] .btn { border-radius: 2px; }
```

Custom CSS cannot load remote resources (CSP); images must be theme assets.

## Image and video backgrounds

Add a file via the editor (*Background → Choose file*) — it is copied to `<data>/themes/assets/`
and referenced by name in `background.asset`. Supported: PNG, JPEG, WebP, GIF, AVIF, MP4, WebM.
Videos are muted, looped and paused while the window is hidden. Use `dim` to keep text readable.

## Export and share

*Export…* in the editor writes one self-contained JSON bundle: the theme fields, plus
`customCss` (if any) and `embeddedAssets` (`{ "file name": "data:<mime>;base64,…" }`) for the
background. Importing a bundle restores all three; the imported theme gets a fresh id if one
with the same name already exists.

## Follow system light/dark

Settings → Appearance → *Follow system light/dark mode* switches between the chosen light and dark
themes whenever the OS changes its colour scheme.

## Accessibility checklist

- Text contrast: aim for ≥ 4.5 : 1 for `text` on `bg`/`surface`, ≥ 3 : 1 for `textMuted`, and
  `accentText` on `accent` ≥ 4.5 : 1.
- Keep `focus` clearly distinct from `accent` — it marks keyboard focus.
- Animations stop automatically when the OS requests reduced motion, whatever the theme says.
- `layout.scale` and `font.size` let low-vision users enlarge the whole UI.

## What the player can override
Menu position, style, icon size, dividers and the effects switch are theme settings that the player
may override in Settings → Appearance ("From theme" is the default for each). The mod follows the
theme's `motion.effects` for its springy menu and fading HUD modules unless the HUD file sets its own
`animations`.
