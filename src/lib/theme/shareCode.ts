// A theme as a short text to paste in a chat: "aerion1:" + the theme JSON, compressed and in
// URL-safe base64. Only colours, fonts, sizes and the like travel this way: custom CSS and
// background pictures or videos stay behind (they don't fit, and CSS from a stranger is unsafe).

const PREFIX = "aerion1:";
/** The longest code accepted, and the largest theme JSON it may unpack to. */
const MAX_CODE = 24_000;
const MAX_JSON = 64_000;

export function toBase64Url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromBase64Url(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
  const bin = atob(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export async function pipe(
  bytes: Uint8Array,
  stream: CompressionStream | DecompressionStream,
): Promise<Uint8Array> {
  const out = new Blob([bytes as BlobPart]).stream().pipeThrough(stream);
  return new Uint8Array(await new Response(out).arrayBuffer());
}

/** What may be shared of a theme: no CSS, no background file, no `$schema`. */
export function shareable(theme: Record<string, unknown>): Record<string, unknown> {
  const { $schema: _schema, css: _css, ...rest } = theme;
  const bg = rest.background;
  if (bg && typeof bg === "object") {
    const b = { ...(bg as Record<string, unknown>) };
    if (b.type === "image" || b.type === "video")
      b.type = typeof b.gradient === "string" ? "gradient" : "color";
    b.asset = null;
    rest.background = b;
  }
  return rest;
}

export async function encodeTheme(theme: Record<string, unknown>): Promise<string> {
  const json = new TextEncoder().encode(JSON.stringify(shareable(theme)));
  return PREFIX + toBase64Url(await pipe(json, new CompressionStream("deflate-raw")));
}

/** The theme in a code (already made shareable), or an error naming what is wrong. */
export async function decodeTheme(code: string): Promise<Record<string, unknown>> {
  const s = code.trim();
  if (!s.startsWith(PREFIX)) throw new Error("not-a-theme-code");
  if (s.length > MAX_CODE) throw new Error("too-long");
  let raw: Uint8Array;
  try {
    raw = await pipe(fromBase64Url(s.slice(PREFIX.length)), new DecompressionStream("deflate-raw"));
  } catch {
    throw new Error("damaged");
  }
  if (raw.length > MAX_JSON) throw new Error("too-long");
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    throw new Error("damaged");
  }
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("damaged");
  return shareable(parsed as Record<string, unknown>);
}

/** A theme id from its name that `taken` doesn't use yet. */
export function freeThemeId(name: string, taken: (id: string) => boolean): string {
  const stem =
    name
      .toLowerCase()
      .normalize("NFD")
      .replace(/[\u0300-\u036f]/g, "")
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-|-$/g, "")
      .slice(0, 40) || "theme";
  let id = stem;
  for (let n = 2; taken(id); n++) id = `${stem}-${n}`;
  return id;
}
