// The user's arrangement of the sidebar and the home screen (saved in settings.layout).
import type { Page } from "./router.svelte";
import { app } from "./app.svelte";
import { themes } from "../theme/engine.svelte";
import type { LayoutItem } from "../bindings";

export interface NavDef {
  id: string;
  page: Page;
  icon: string;
  key: string;
  /** Shown unless the user hides it. */
  visible: boolean;
  /** Can't be hidden (so nobody locks themselves out). */
  locked?: boolean;
}

export const NAV: NavDef[] = [
  { id: "home", page: "home", icon: "home", key: "nav.home", visible: true, locked: true },
  { id: "instances", page: "instances", icon: "grid", key: "nav.instances", visible: true },
  { id: "mods", page: "mods", icon: "package", key: "nav.mods", visible: true },
  { id: "skins", page: "skins", icon: "shirt", key: "nav.skins", visible: true },
  { id: "friends", page: "friends", icon: "users", key: "nav.friends", visible: true },
  { id: "stats", page: "stats", icon: "chart", key: "nav.stats", visible: true },
  { id: "screenshots", page: "screenshots", icon: "image", key: "nav.screenshots", visible: true },
  { id: "performance", page: "performance", icon: "cpu", key: "nav.performance", visible: true },
  // The account button at the bottom of the sidebar opens Accounts too.
  { id: "accounts", page: "accounts", icon: "user", key: "nav.accounts", visible: false },
  { id: "console", page: "console", icon: "terminal", key: "nav.console", visible: true },
  { id: "settings", page: "settings", icon: "settings", key: "nav.settings", visible: true, locked: true },
];

export interface HomeDef {
  id: string;
  key: string;
  icon: string;
  visible: boolean;
  locked?: boolean;
}

export const HOME: HomeDef[] = [
  { id: "greeting", key: "layout.home.greeting", icon: "sparkles", visible: true },
  { id: "play", key: "layout.home.play", icon: "play", visible: true, locked: true },
  { id: "actions", key: "layout.home.actions", icon: "command", visible: true },
  { id: "summary", key: "layout.home.summary", icon: "chart", visible: false },
  { id: "recent", key: "layout.home.recent", icon: "star", visible: true },
];

/** Saved order and visibility applied to the definitions; unknown ids dropped, new ones appended. */
function merge<T extends { id: string; visible: boolean; locked?: boolean }>(
  defs: T[],
  saved: LayoutItem[],
): T[] {
  // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
  const byId = new Map(defs.map((d) => [d.id, d]));
  const out: T[] = [];
  for (const s of saved) {
    const d = byId.get(s.id);
    if (!d) continue;
    out.push({ ...d, visible: d.locked ? true : s.visible });
    byId.delete(s.id);
  }
  return [...out, ...byId.values()];
}

export type PlayerSide = "right" | "left" | "hidden";
export type NavSide = "theme" | "left" | "right" | "top" | "bottom";
export type NavMode = "fixed" | "hover" | "icons";
export const NAV_SIDES: NavSide[] = ["theme", "left", "right", "top", "bottom"];
export const NAV_MODES: NavMode[] = ["fixed", "hover", "icons"];

class Layout {
  get nav(): NavDef[] {
    return merge(NAV, app.settings?.layout.nav ?? []);
  }
  get home(): HomeDef[] {
    return merge(HOME, app.settings?.layout.home ?? []);
  }
  get navFavorites(): boolean {
    return app.settings?.layout.navFavorites ?? true;
  }
  get player(): PlayerSide {
    return (app.settings?.layout.homePlayer as PlayerSide) ?? "right";
  }

  // Each menu option is the player's own choice, or "theme": whatever the active theme says.
  /** The player's choice for where the menu sits ("theme" follows the theme). */
  get navSide(): NavSide {
    const v = app.settings?.layout.navSide;
    return NAV_SIDES.includes(v as NavSide) ? (v as NavSide) : "theme";
  }
  /** Where the menu actually sits. */
  get side(): Exclude<NavSide, "theme"> {
    return this.navSide === "theme" ? themes.active.layout.sidebar : this.navSide;
  }
  get navMode(): NavMode | "theme" {
    const v = app.settings?.layout.navMode;
    return NAV_MODES.includes(v as NavMode) ? (v as NavMode) : "theme";
  }
  get mode(): NavMode {
    return this.navMode === "theme" ? themes.active.layout.navMode : this.navMode;
  }
  /** The player's icon size; 0 follows the theme. */
  get navIconSize(): number {
    const v = app.settings?.layout.navIconSize ?? 0;
    return v === 0 ? 0 : Math.min(26, Math.max(15, v));
  }
  get iconSize(): number {
    return this.navIconSize || themes.active.layout.navIconSize;
  }
  /** The player's dividers, or null to follow the theme. */
  get navGaps(): string[] | null {
    return app.settings?.layout.navGaps ?? null;
  }
  get gaps(): string[] {
    return this.navGaps ?? themes.active.layout.navGaps;
  }
  get effectsPref(): "theme" | "on" | "off" {
    const v = app.settings?.layout.effects;
    return v === "on" || v === "off" ? v : "theme";
  }
  /** Springy animations and the other effects are on (before reduced motion is considered). */
  get springy(): boolean {
    return this.effectsPref === "theme" ? themes.active.motion.effects : this.effectsPref === "on";
  }

  private async save(patch: Partial<NonNullable<typeof app.settings>["layout"]>) {
    const current = app.settings?.layout;
    if (!current) return;
    await app.saveSettings({ layout: { ...current, ...patch } });
  }

  saveNav(items: { id: string; visible: boolean }[]) {
    return this.save({ nav: items.map(({ id, visible }) => ({ id, visible })) });
  }
  saveHome(items: { id: string; visible: boolean }[]) {
    return this.save({ home: items.map(({ id, visible }) => ({ id, visible })) });
  }
  setNavFavorites(on: boolean) {
    return this.save({ navFavorites: on });
  }
  setPlayer(side: PlayerSide) {
    return this.save({ homePlayer: side });
  }
  setNavSide(v: NavSide) {
    return this.save({ navSide: v });
  }
  setNavMode(v: NavMode | "theme") {
    return this.save({ navMode: v });
  }
  setNavIconSize(v: number) {
    return this.save({ navIconSize: Math.round(v) });
  }
  setNavGaps(ids: string[] | undefined) {
    return this.save({ navGaps: ids });
  }
  setEffects(v: "theme" | "on" | "off") {
    return this.save({ effects: v });
  }
  resetNav() {
    return this.save({
      nav: [],
      navFavorites: true,
      navSide: "theme",
      navMode: "theme",
      navIconSize: 0,
      navGaps: undefined,
      effects: "theme",
    });
  }
  resetHome() {
    return this.save({ home: [], homePlayer: "right" });
  }
}

export const layout = new Layout();
