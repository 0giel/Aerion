// The user's arrangement of the sidebar and the home screen (saved in settings.layout).
import type { Page } from "./router.svelte";
import { app } from "./app.svelte";
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
  { id: "stats", page: "stats", icon: "chart", key: "nav.stats", visible: true },
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
  resetNav() {
    return this.save({ nav: [], navFavorites: true });
  }
  resetHome() {
    return this.save({ home: [], homePlayer: "right" });
  }
}

export const layout = new Layout();
