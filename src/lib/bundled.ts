// Mods installed together with a new instance, from Modrinth on the player's computer (Aerion redistributes none of them). They are ordinary content afterwards:
// listed, updated and removable in the instance's Content tab.
import { commands } from "./api";
import { app } from "./stores/app.svelte";

/** e4mc (MIT): a public address for a world opened to friends, used by "Host a world". */
const E4MC = "qANg5Jrr";

/**
 * New-instance extras, one after the other (two tasks writing the content list at once could
 * lose an entry): e4mc for "Fabric + Aerion" (it pulls in Fabric API), then the performance pack
 * when wanted (`installBoostPack`: its mods are listed in launcher-core `mods::boost::PACK`).
 * Resolves with the task ids once every task has started; failures leave a normal instance.
 */
export async function installBundled(
  instanceId: string,
  aerion: boolean,
  optimize: boolean,
): Promise<string[]> {
  const ids: string[] = [];
  if (aerion) {
    ids.push(await commands.installContent(instanceId, "modrinth", E4MC, null, "mod", [], "e4mc"));
  }
  if (optimize) {
    await waitForTasks(ids);
    ids.push(await commands.installBoostPack(instanceId));
  }
  return ids;
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
