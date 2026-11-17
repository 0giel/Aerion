// An instance as a text to paste in a chat: "aerionI1:" + its game version, loader, memory and the
// exact mods, resource packs and shaders (by their Modrinth or CurseForge ids), compressed. A friend
// pastes it in Instances and gets the same set. Files added by hand have no ids and stay behind.

import type {
  ContentEntry_Serialize as ContentEntry,
  ContentKind,
  Instance_Serialize as Instance,
  LoaderKind,
  Provider,
} from "./bindings";
import { fromBase64Url, pipe, toBase64Url } from "./theme/shareCode";

const PREFIX = "aerionI1:";
const MAX_CODE = 40_000;
const MAX_JSON = 200_000;
const KINDS: ContentKind[] = ["mod", "resourcePack", "shader", "dataPack"];
const LOADERS: LoaderKind[] = ["vanilla", "fabric", "quilt", "forge", "neoforge"];

export type SharedItem = {
  provider: Provider;
  projectId: string;
  versionId: string;
  kind: ContentKind;
  title: string;
};

export type SharedInstance = {
  name: string;
  gameVersion: string;
  loader: LoaderKind;
  loaderVersion: string | null;
  aerionMod: boolean;
  maxMemoryMb: number | null;
  items: SharedItem[];
};

/** The code, and how many enabled files couldn't go in (added by hand, without ids). */
export async function encodeInstance(
  inst: Instance,
  entries: ContentEntry[],
): Promise<{ code: string; left: number }> {
  const on = entries.filter((e) => e.enabled && KINDS.includes(e.kind));
  const items = on.filter((e) => e.provider && e.projectId && e.versionId);
  const data = {
    n: inst.name,
    g: inst.gameVersion,
    l: inst.loader,
    lv: inst.loaderVersion ?? null,
    a: !!(inst as { aerionMod?: boolean }).aerionMod,
    m: inst.maxMemoryMb ?? null,
    c: items.map((e) => [e.provider === "curseforge" ? "c" : "m", e.projectId, e.versionId, e.kind, e.title]),
  };
  const json = new TextEncoder().encode(JSON.stringify(data));
  return {
    code: PREFIX + toBase64Url(await pipe(json, new CompressionStream("deflate-raw"))),
    left: on.length - items.length,
  };
}

const str = (v: unknown, max: number): string | null =>
  typeof v === "string" && v.length > 0 && v.length <= max ? v : null;

/** What a code holds, or an error key: "not-an-instance-code", "too-long", "damaged". */
export async function decodeInstance(code: string): Promise<SharedInstance> {
  const s = code.trim();
  if (!s.startsWith(PREFIX)) throw new Error("not-an-instance-code");
  if (s.length > MAX_CODE) throw new Error("too-long");
  let raw: Uint8Array;
  try {
    raw = await pipe(fromBase64Url(s.slice(PREFIX.length)), new DecompressionStream("deflate-raw"));
  } catch {
    throw new Error("damaged");
  }
  if (raw.length > MAX_JSON) throw new Error("too-long");
  let d: Record<string, unknown>;
  try {
    d = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    throw new Error("damaged");
  }
  const name = str(d?.n, 80),
    game = str(d?.g, 40),
    loader = d?.l as LoaderKind;
  if (!name || !game || !LOADERS.includes(loader) || !Array.isArray(d.c) || d.c.length > 500)
    throw new Error("damaged");
  const items: SharedItem[] = [];
  for (const row of d.c as unknown[]) {
    if (!Array.isArray(row)) continue;
    const [p, project, version, kind, title] = row;
    const projectId = str(project, 64),
      versionId = str(version, 64);
    if (!projectId || !versionId || !KINDS.includes(kind as ContentKind)) continue;
    items.push({
      provider: p === "c" ? "curseforge" : "modrinth",
      projectId,
      versionId,
      kind: kind as ContentKind,
      title: str(title, 120) ?? projectId,
    });
  }
  const mem = typeof d.m === "number" && d.m >= 512 && d.m <= 65536 ? Math.round(d.m) : null;
  return {
    name,
    gameVersion: game,
    loader,
    loaderVersion: str(d.lv, 60),
    aerionMod: d.a === true,
    maxMemoryMb: mem,
    items,
  };
}
