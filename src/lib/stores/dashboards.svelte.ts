// Experimental widget dashboards: the `home` one can replace the home screen, the others are
// pages of their own in the sidebar. Stored in the launcher settings.
import type { Dashboard, WidgetItem } from "../api";
import { app } from "./app.svelte";
import { freeSpot } from "../dashboard/layout";
import { WIDGETS } from "../dashboard/widgets";

export const HOME_ID = "home";

const uid = () => Math.random().toString(36).slice(2, 10);

function widget(kind: string, x: number, y: number, w: number, h: number): WidgetItem {
  return { id: uid(), kind, x, y, w, h, config: {} };
}

/** A home dashboard that looks like the classic home screen to start from. */
function defaultHome(): Dashboard {
  return {
    id: HOME_ID,
    name: "",
    icon: "home",
    widgets: [
      widget("greeting", 0, 0, 8, 2),
      widget("player", 8, 0, 4, 5),
      widget("play", 0, 2, 8, 2),
      widget("recent", 0, 4, 8, 3),
      widget("stats", 8, 5, 4, 2),
    ],
  };
}

class Dashboards {
  get enabled() {
    return !!app.settings?.experimentalWidgets;
  }
  get homeActive() {
    return this.enabled && !!app.settings?.homeDashboard;
  }
  get list(): Dashboard[] {
    return app.settings?.dashboards ?? [];
  }
  /** Pages shown in the sidebar (everything but the home dashboard). */
  get pages(): Dashboard[] {
    return this.enabled ? this.list.filter((d) => d.id !== HOME_ID) : [];
  }

  get(id: string): Dashboard | undefined {
    return this.list.find((d) => d.id === id) ?? (id === HOME_ID ? defaultHome() : undefined);
  }

  async save(d: Dashboard) {
    const list = this.list.some((x) => x.id === d.id)
      ? this.list.map((x) => (x.id === d.id ? d : x))
      : [...this.list, d];
    await app.saveSettings({ dashboards: list });
  }

  async create(name: string): Promise<string> {
    const id = `p${uid()}`;
    await this.save({ id, name: name.slice(0, 40), icon: "grid", widgets: [] });
    return id;
  }

  async remove(id: string) {
    await app.saveSettings({ dashboards: this.list.filter((d) => d.id !== id) });
  }

  /** A widget of `kind` at the first free place. */
  add(d: Dashboard, kind: string): Dashboard {
    const def = WIDGETS[kind];
    const spot = freeSpot(d.widgets, def.size.w, def.size.h);
    return { ...d, widgets: [...d.widgets, widget(kind, spot.x, spot.y, def.size.w, def.size.h)] };
  }
}

export const dashboards = new Dashboards();
