// What the launcher knows about the Aerion mod's HUD modules: options per module (stored as
// strings in hud.json), sample text for the preview, and the ready-made layouts.
import type { HudConfig } from "../api";

export type Field =
  | { key: string; type: "text"; def: string }
  | { key: string; type: "toggle"; def: "1" | "0" }
  | { key: string; type: "select"; def: string; choices: string[] }
  | { key: string; type: "number"; def: string; min: number; max: number }
  | { key: string; type: "color"; def: string };

export const MODULE_IDS = [
  "fps",
  "coords",
  "direction",
  "cps",
  "keystrokes",
  "armor",
  "potions",
  "ping",
  "clock",
  "memory",
  "minimap",
  "toggles",
] as const;

export const FIELDS: Record<string, Field[]> = {
  fps: [
    { key: "format", type: "text", def: "{fps} FPS" },
    { key: "colored", type: "toggle", def: "0" },
  ],
  cps: [{ key: "format", type: "text", def: "{left} | {right} CPS" }],
  coords: [
    { key: "direction", type: "toggle", def: "1" },
    { key: "decimals", type: "toggle", def: "0" },
    { key: "lines", type: "toggle", def: "0" },
    { key: "nether", type: "toggle", def: "0" },
    { key: "biome", type: "toggle", def: "0" },
  ],
  direction: [{ key: "degrees", type: "toggle", def: "1" }],
  ping: [
    { key: "format", type: "text", def: "{ping} ms" },
    { key: "colored", type: "toggle", def: "0" },
  ],
  clock: [
    { key: "h24", type: "toggle", def: "1" },
    { key: "seconds", type: "toggle", def: "0" },
    { key: "date", type: "toggle", def: "0" },
  ],
  memory: [
    { key: "format", type: "text", def: "{used}/{max} MB" },
    { key: "colored", type: "toggle", def: "0" },
  ],
  potions: [
    { key: "duration", type: "toggle", def: "1" },
    { key: "level", type: "toggle", def: "1" },
    { key: "colors", type: "toggle", def: "1" },
  ],
  armor: [
    { key: "layout", type: "select", def: "vertical", choices: ["vertical", "horizontal"] },
    { key: "durability", type: "select", def: "percent", choices: ["percent", "value", "none"] },
    { key: "colored", type: "toggle", def: "1" },
    { key: "hand", type: "toggle", def: "1" },
    { key: "offhand", type: "toggle", def: "0" },
  ],
  keystrokes: [
    { key: "wasd", type: "toggle", def: "1" },
    { key: "mouse", type: "toggle", def: "1" },
    { key: "space", type: "toggle", def: "1" },
    { key: "cps", type: "toggle", def: "1" },
    { key: "keySize", type: "number", def: "18", min: 12, max: 30 },
    { key: "pressed", type: "color", def: "accent" },
  ],
  minimap: [
    { key: "size", type: "number", def: "80", min: 48, max: 160 },
    { key: "zoom", type: "number", def: "2", min: 1, max: 4 },
    { key: "coords", type: "toggle", def: "1" },
    { key: "north", type: "toggle", def: "1" },
  ],
  toggles: [
    { key: "sprint", type: "toggle", def: "1" },
    { key: "sneak", type: "toggle", def: "1" },
    { key: "fullbright", type: "toggle", def: "1" },
    { key: "freelook", type: "toggle", def: "1" },
  ],
};

const fill = (pattern: string, values: Record<string, string | number>) =>
  pattern.replace(/\{(\w+)\}/g, (m, k: string) => (k in values ? String(values[k]) : m));

/** Text lines the preview shows for a module (the game shows live values). */
export function sample(id: string, opt: (key: string, def: string) => string): string[] {
  switch (id) {
    case "fps":
      return [fill(opt("format", "{fps} FPS"), { fps: 144 })];
    case "cps":
      return [fill(opt("format", "{left} | {right} CPS"), { left: 9, right: 4 })];
    case "coords": {
      const dec = opt("decimals", "0") === "1";
      const lines =
        opt("lines", "0") === "1"
          ? dec
            ? ["X 128.4", "Y 64.0", "Z -32.5"]
            : ["X 128", "Y 64", "Z -33"]
          : [dec ? "XYZ 128.4 / 64.0 / -32.5" : "XYZ 128 / 64 / -33"];
      if (opt("biome", "0") === "1") lines.push("plains");
      if (opt("direction", "1") === "1") lines.push("N");
      if (opt("nether", "0") === "1") lines.push("Nether 16 / -4");
      return lines;
    }
    case "direction":
      return [opt("degrees", "1") === "1" ? "NE  135°" : "NE"];
    case "ping":
      return [fill(opt("format", "{ping} ms"), { ping: 42 })];
    case "clock": {
      const time = opt("h24", "1") === "1" ? (opt("seconds", "0") === "1" ? "21:37:05" : "21:37") : "9:37 PM";
      return opt("date", "0") === "1" ? [time, "01.11.2026"] : [time];
    }
    case "memory":
      return [fill(opt("format", "{used}/{max} MB"), { used: 1840, max: 6144, percent: 30 })];
    case "potions":
      return [
        `Speed${opt("level", "1") === "1" ? " II" : ""}${opt("duration", "1") === "1" ? "  1:30" : ""}`,
        `Night Vision${opt("duration", "1") === "1" ? "  6:12" : ""}`,
      ];
    case "toggles":
      return ["Sprinting (toggled)"];
    default:
      return [];
  }
}

type Preset = (cfg: HudConfig) => HudConfig;
const only =
  (ids: string[], extra?: (c: HudConfig) => void): Preset =>
  (cfg) => {
    const c: HudConfig = structuredClone(cfg);
    for (const [id, m] of Object.entries(c.modules)) if (m) m.enabled = ids.includes(id);
    extra?.(c);
    return c;
  };

export const PRESETS: Record<string, Preset> = {
  minimal: only(["fps", "coords"], (c) => {
    c.style.background = "none";
  }),
  pvp: only(["fps", "cps", "keystrokes", "armor", "potions", "ping", "toggles"], (c) => {
    c.style.background = "theme";
    c.features.zoom.enabled = true;
  }),
  survival: only(["fps", "coords", "direction", "armor", "potions", "minimap", "clock", "toggles"]),
  off: only([]),
};
