import { describe, expect, it } from "vitest";
import en from "./en";
import pl from "./pl";
import { INSTANCE_ICONS } from "../instanceIcons";
// The engine's error codes and crash kinds, read from the Rust sources so a new code without a
// translation fails here.
import errorRs from "../../../crates/launcher-core/src/error.rs?raw";
import crashRs from "../../../crates/launcher-core/src/game/crash.rs?raw";
import gamesRs from "../../../src-tauri/src/games.rs?raw";

type Tree = { [key: string]: string | Tree };

const isPlural = (v: unknown): v is Record<string, string> =>
  !!v && typeof v === "object" && "one" in v && "other" in v;

/** Leaf paths; plural objects count as one leaf. */
function leaves(tree: Tree, prefix = ""): Map<string, string | Record<string, string>> {
  const out = new Map<string, string | Record<string, string>>();
  for (const [k, v] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${k}` : k;
    if (typeof v === "string" || isPlural(v)) out.set(path, v as string | Record<string, string>);
    else for (const [p, l] of leaves(v, path)) out.set(p, l);
  }
  return out;
}

const placeholders = (v: string | Record<string, string>): Set<string> =>
  new Set(
    (typeof v === "string" ? [v] : Object.values(v)).flatMap((s) =>
      [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]),
    ),
  );

// Every component and module, to check the keys passed to `t()`.
const sources = import.meta.glob(["../**/*.svelte", "../**/*.ts", "../../*.svelte", "!../**/*.test.ts"], {
  query: "?raw",
  import: "default",
  eager: true,
}) as Record<string, string>;

const enLeaves = leaves(en as unknown as Tree);
const plLeaves = leaves(pl as unknown as Tree);

describe("translations", () => {
  it("contain every literal key used with t()", () => {
    expect(Object.keys(sources).length).toBeGreaterThan(30);
    const groups = new Set([...enLeaves.keys()].map((k) => k.split(".")[0]));
    const missing = new Set<string>();
    for (const [file, text] of Object.entries(sources)) {
      for (const m of text.matchAll(/\bt\(\s*"([a-zA-Z0-9_.]+)"/g)) {
        const key = m[1];
        // Only keys of known groups (`t("x")` may also be another function's call).
        if (groups.has(key.split(".")[0]) && !enLeaves.has(key)) missing.add(`${key} (${file})`);
      }
    }
    expect([...missing]).toEqual([]);
  });

  it("contain every built-in instance icon name", () => {
    const keys = Object.keys(INSTANCE_ICONS).map((k) => `iconPicker.icons.${k}`);
    expect(keys.filter((k) => !enLeaves.has(k))).toEqual([]);
  });

  it("have the same keys", () => {
    expect([...plLeaves.keys()].sort()).toEqual([...enLeaves.keys()].sort());
  });

  it("use the same placeholders", () => {
    const mismatched: string[] = [];
    for (const [key, enValue] of enLeaves) {
      const plValue = plLeaves.get(key);
      if (plValue === undefined) continue;
      const a = placeholders(enValue);
      const b = placeholders(plValue);
      // Plural forms may omit the count ("wczoraj" instead of "1 dzień temu").
      if (isPlural(enValue)) {
        a.delete("n");
        b.delete("n");
      }
      if ([...a].sort().join() !== [...b].sort().join()) mismatched.push(key);
    }
    expect(mismatched).toEqual([]);
  });

  it("have no empty strings", () => {
    const empty = [...enLeaves, ...plLeaves]
      .filter(([, v]) => (typeof v === "string" ? !v.trim() : Object.values(v).some((s) => !s.trim())))
      .map(([k]) => k);
    expect(empty).toEqual([]);
  });

  it("give every Polish plural the one/few/many/other forms", () => {
    const incomplete = [...plLeaves]
      .filter(([, v]) => isPlural(v))
      .filter(([, v]) => !["one", "few", "many", "other"].every((f) => f in (v as object)))
      .map(([k]) => k);
    expect(incomplete).toEqual([]);
  });

  it("cover every error code and crash kind produced by the engine", () => {
    const codes = [...errorRs.matchAll(/=> "([a-z]+\.[a-zA-Z]+)"/g)].map((m) => m[1]);
    expect(codes.length).toBeGreaterThan(30);
    expect(codes.filter((c) => !enLeaves.has(`errors.${c}`))).toEqual([]);

    const analyze = crashRs.slice(crashRs.indexOf("pub fn analyze"), crashRs.indexOf("#[cfg(test)]"));
    const kinds = [
      ...[...analyze.matchAll(/^\s+"([a-zA-Z]+)"$/gm)].map((m) => m[1]),
      ...[...gamesRs.matchAll(/kind: "([a-zA-Z]+)"/g)].map((m) => m[1]),
    ];
    expect(kinds).toContain("outOfMemory");
    expect(kinds).toContain("startFailed");
    expect(kinds.filter((k) => !enLeaves.has(`crash.kinds.${k}`))).toEqual([]);
  });
});
