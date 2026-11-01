// The widget catalogue: what each kind is called, its icon and default/minimum size (columns of
// a 12-column grid × rows of ~72 px). Components are loaded on demand.
import type { Component } from "svelte";

export interface WidgetProps {
  config: Record<string, string>;
  edit: boolean;
  onconfig: (config: Record<string, string>) => void;
}

export interface WidgetDef {
  key: string;
  icon: string;
  size: { w: number; h: number };
  min: { w: number; h: number };
  load: () => Promise<{ default: Component<WidgetProps> }>;
}

export const WIDGETS: Record<string, WidgetDef> = {
  greeting: {
    key: "widgets.kinds.greeting",
    icon: "sparkles",
    size: { w: 6, h: 2 },
    min: { w: 3, h: 1 },
    load: () => import("./widgets/Greeting.svelte"),
  },
  play: {
    key: "widgets.kinds.play",
    icon: "play",
    size: { w: 6, h: 2 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Play.svelte"),
  },
  recent: {
    key: "widgets.kinds.recent",
    icon: "grid",
    size: { w: 6, h: 3 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Recent.svelte"),
  },
  player: {
    key: "widgets.kinds.player",
    icon: "user",
    size: { w: 3, h: 5 },
    min: { w: 2, h: 3 },
    load: () => import("./widgets/Player.svelte"),
  },
  stats: {
    key: "widgets.kinds.stats",
    icon: "chart",
    size: { w: 4, h: 2 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Stats.svelte"),
  },
  perf: {
    key: "widgets.kinds.perf",
    icon: "cpu",
    size: { w: 4, h: 3 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Perf.svelte"),
  },
  server: {
    key: "widgets.kinds.server",
    icon: "monitor",
    size: { w: 4, h: 2 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Server.svelte"),
  },
  notes: {
    key: "widgets.kinds.notes",
    icon: "pencil",
    size: { w: 4, h: 3 },
    min: { w: 2, h: 2 },
    load: () => import("./widgets/Notes.svelte"),
  },
  clock: {
    key: "widgets.kinds.clock",
    icon: "clock",
    size: { w: 3, h: 2 },
    min: { w: 2, h: 1 },
    load: () => import("./widgets/Clock.svelte"),
  },
  updates: {
    key: "widgets.kinds.updates",
    icon: "arrowUp",
    size: { w: 4, h: 2 },
    min: { w: 3, h: 2 },
    load: () => import("./widgets/Updates.svelte"),
  },
  actions: {
    key: "widgets.kinds.actions",
    icon: "command",
    size: { w: 6, h: 2 },
    min: { w: 3, h: 1 },
    load: () => import("./widgets/Actions.svelte"),
  },
};
