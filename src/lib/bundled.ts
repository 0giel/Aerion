// Mods installed together with the Aerion mod in a new "Fabric + Aerion" instance, from Modrinth on
// the player's computer (Aerion redistributes none of them). They are ordinary content afterwards:
// listed, updated and removable in the instance's Content tab.
import { commands } from "./api";
import { app } from "./stores/app.svelte";

/** e4mc (MIT): a public address for a world opened to friends, used by "Host a world". */
const E4MC = "qANg5Jrr";

/**
 * Speed without changing how the game plays, all checked with the Aerion mod on 26.3 and 1.21.11:
 * Sodium (rendering), Lithium (game logic, made to match vanilla), FerriteCore (memory) and
 * ImmediatelyFast (HUD, text and interface drawing). None needs another mod.
 */
export const OPTIMIZATION = [
  { id: "AANobbMI", name: "Sodium" },
  { id: "gvQqBUqZ", name: "Lithium" },
  { id: "uXXizFIs", name: "FerriteCore" },
  { id: "5ZwdcRci", name: "ImmediatelyFast" },
] as const;

/**
 * One install task: e4mc (it pulls in Fabric API), plus the optimization mods when wanted. A mod
 * without a version for this game is skipped rather than failing the rest. Returns the task ids.
 */
export async function installBundled(instanceId: string, optimize: boolean): Promise<string[]> {
  const extra = optimize ? OPTIMIZATION.map((m) => m.id) : [];
  const title = ["e4mc", ...(optimize ? OPTIMIZATION.map((m) => m.name) : [])].join(", ");
  return [await commands.installContent(instanceId, "modrinth", E4MC, null, "mod", extra, title)];
}

/** Resolves when none of the tasks is still running (or after `timeoutMs`). */
export function waitForTasks(ids: string[], timeoutMs = 180_000): Promise<void> {
  const busy = () => app.tasks.some((x) => ids.includes(x.id) && x.status === "running");
  return new Promise((resolve) => {
    const started = Date.now();
    const check = () => {
      // A task that has not reported yet counts as running for the first moments.
      const unseen = ids.some((id) => !app.tasks.some((x) => x.id === id)) && Date.now() - started < 3000;
      if ((!busy() && !unseen) || Date.now() - started > timeoutMs) resolve();
      else setTimeout(check, 300);
    };
    check();
  });
}
