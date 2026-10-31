// Applies the active theme: CSS variables on <html>, data attributes for layout switches,
// the theme's custom CSS, and a live-preview override used by the theme editor.
import { commands, events, type ThemeFile } from "../api";
import { normalizeTheme, themeToCss, type Theme } from "./schema";

const files = import.meta.glob(["../../../themes/*.json", "!../../../themes/theme.schema.json"], {
  eager: true,
  import: "default",
});

const ORDER = ["nocturne", "dark", "light", "amoled", "emerald", "rose", "amber"];

function loadBuiltins(): Theme[] {
  const raw = Object.values(files) as unknown[];
  const first = raw.find((t) => (t as { id?: string }).id === "nocturne") ?? raw[0];
  const base = normalizeTheme(first, FALLBACK);
  const rank = (id: string) => (ORDER.includes(id) ? ORDER.indexOf(id) : ORDER.length);
  return raw.map((t) => normalizeTheme(t, base)).sort((a, b) => rank(a.id) - rank(b.id));
}

const FALLBACK: Theme = {
  id: "nocturne",
  name: "Nocturne",
  base: "dark",
  colors: {
    bg: "#16171b",
    surface: "#1f2026",
    surface2: "#272930",
    surface3: "#31333b",
    border: "#ffffff17",
    text: "#ecebf3",
    textMuted: "#a9a8b6",
    textFaint: "#74737f",
    accent: "#a395ff",
    accentText: "#140f2e",
    success: "#4ade80",
    warning: "#fbbf24",
    danger: "#f87171",
    info: "#60a5fa",
    focus: "#c4baff",
  },
  radius: { sm: 6, md: 10, lg: 16, xl: 22 },
  font: { family: "system", mono: "system-mono", size: 14 },
  density: "comfortable",
  layout: { sidebar: "left", scale: 1 },
  effects: { blur: 18, glass: 0.72, shadow: 0.45 },
  motion: { enabled: true, speed: 1 },
  background: {
    type: "gradient",
    color: "#16171b",
    gradient: null,
    asset: null,
    dim: 0,
    blur: 0,
    ribbons: true,
    particles: "sparkles",
  },
};

export interface UserTheme {
  theme: Theme;
  css: string | null;
}

export interface ThemePrefs {
  theme: string;
  themeFollowSystem: boolean;
  themeLight: string;
  themeDark: string;
}

class ThemeEngine {
  readonly builtins: Theme[] = loadBuiltins();
  user = $state.raw<UserTheme[]>([]);
  current = $state.raw<Theme>(this.builtins[0] ?? FALLBACK);
  currentCss = $state<string | null>(null);
  /** Unsaved theme from the editor, shown instead of `current`. */
  preview = $state.raw<Theme | null>(null);
  previewCss = $state<string | null>(null);
  systemDark = $state(true);
  reducedMotion = $state(false);
  private prefs: ThemePrefs | null = null;
  private styleEl: HTMLStyleElement | null = null;

  get active(): Theme {
    return this.preview ?? this.current;
  }

  get all(): Theme[] {
    return [...this.builtins, ...this.user.map((u) => u.theme)];
  }

  isBuiltin(id: string): boolean {
    return this.builtins.some((t) => t.id === id);
  }

  find(id: string): Theme | undefined {
    return this.all.find((t) => t.id === id);
  }

  async init() {
    const dark = matchMedia("(prefers-color-scheme: dark)");
    const motion = matchMedia("(prefers-reduced-motion: reduce)");
    this.systemDark = dark.matches;
    this.reducedMotion = motion.matches;
    dark.addEventListener("change", (e) => {
      this.systemDark = e.matches;
      this.resolve();
    });
    motion.addEventListener("change", (e) => {
      this.reducedMotion = e.matches;
      this.apply();
    });
    await this.loadUser();
    void events.themesChangedEvent.listen(async () => {
      await this.loadUser();
      this.resolve();
    });
  }

  async loadUser() {
    try {
      const list: ThemeFile[] = await commands.listUserThemes();
      const base = this.builtins[0] ?? FALLBACK;
      this.user = list.flatMap((f) => {
        try {
          const theme = normalizeTheme({ ...JSON.parse(f.json), id: f.id }, base);
          return [{ theme, css: f.css }];
        } catch {
          return [];
        }
      });
    } catch {
      this.user = [];
    }
  }

  /** Apply settings (called whenever they change). */
  sync(prefs: ThemePrefs) {
    this.prefs = prefs;
    this.resolve();
  }

  private resolve() {
    const p = this.prefs;
    const id = !p
      ? "nocturne"
      : p.themeFollowSystem
        ? this.systemDark
          ? p.themeDark
          : p.themeLight
        : p.theme;
    const theme = this.find(id) ?? this.builtins[0] ?? FALLBACK;
    this.current = theme;
    this.currentCss = this.user.find((u) => u.theme.id === theme.id)?.css ?? null;
    this.apply();
  }

  setPreview(theme: Theme | null, css: string | null = null) {
    this.preview = theme;
    this.previewCss = css;
    this.apply();
  }

  apply() {
    const t = this.active;
    const root = document.documentElement;
    for (const [k, v] of Object.entries(themeToCss(t, this.reducedMotion))) root.style.setProperty(k, v);
    root.dataset.base = t.base;
    root.dataset.density = t.density;
    root.dataset.sidebar = t.layout.sidebar;
    root.dataset.motion = t.motion.enabled && !this.reducedMotion ? "on" : "off";
    root.style.colorScheme = t.base;
    const css = this.preview ? this.previewCss : this.currentCss;
    if (!this.styleEl) {
      this.styleEl = document.createElement("style");
      this.styleEl.id = "aerion-theme-css";
      document.head.appendChild(this.styleEl);
    }
    this.styleEl.textContent = css ?? "";
  }
}

export const themes = new ThemeEngine();
export { FALLBACK as FALLBACK_THEME };
