<script lang="ts">
  // A friend's instance from a code: what it holds, then one click makes it and installs it all.
  import Modal from "../../components/Modal.svelte";
  import { app } from "../../stores/app.svelte";
  import { router } from "../../stores/router.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage } from "../../api";
  import { t } from "../../i18n/index.svelte";
  import { decodeInstance, type SharedInstance } from "../../instanceCode";

  let { open = $bindable(false) }: { open?: boolean } = $props();
  let text = $state("");
  let shared = $state.raw<SharedInstance | null>(null);
  let problem = $state<string | null>(null);
  let busy = $state(false);

  $effect(() => {
    const code = text.trim();
    if (!code) {
      shared = null;
      problem = null;
      return;
    }
    decodeInstance(code)
      .then((s) => {
        shared = s;
        problem = null;
      })
      .catch((e: Error) => {
        shared = null;
        problem = t(`instances.codeErrors.${e.message}` as never);
      });
  });

  const count = (kind: string) => shared?.items.filter((i) => i.kind === kind).length ?? 0;

  async function create() {
    if (!shared) return;
    busy = true;
    try {
      let inst = await commands.createInstance({
        name: shared.name,
        gameVersion: shared.gameVersion,
        loader: shared.loader,
        loaderVersion: shared.loaderVersion,
        aerionMod: shared.aerionMod,
      });
      if (shared.maxMemoryMb)
        inst = await commands.updateInstance({ ...inst, maxMemoryMb: shared.maxMemoryMb });
      if (shared.items.length) await commands.installSharedContent(inst.id, shared.items);
      await app.refreshInstances();
      open = false;
      text = "";
      router.go("instance", { id: inst.id, tab: "content" });
    } catch (e) {
      toasts.error(errorMessage(e));
    } finally {
      busy = false;
    }
  }
</script>

<Modal bind:open title={t("instances.pasteTitle")}>
  <div class="col gap-3">
    <div class="field">
      <label for="paste-code">{t("instances.codeLabel")}</label>
      <textarea id="paste-code" class="input mono code" rows="4" placeholder="aerionI1:…" bind:value={text}
      ></textarea>
      <span class="tiny muted">{t("instances.pasteHint")}</span>
    </div>
    {#if problem}
      <span class="small danger">{problem}</span>
    {:else if shared}
      <div class="card col gap-1 summary">
        <strong>{shared.name}</strong>
        <span class="small muted"
          >Minecraft {shared.gameVersion} · {shared.loader}{shared.loaderVersion
            ? ` ${shared.loaderVersion}`
            : ""}</span
        >
        <span class="small">
          {t("instances.pasteHolds", {
            mods: count("mod"),
            packs: count("resourcePack"),
            shaders: count("shader"),
          })}
        </span>
      </div>
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button class="btn primary" disabled={!shared || busy} onclick={create}
      >{t("instances.pasteCreate")}</button
    >
  {/snippet}
</Modal>

<style>
  .code {
    width: 100%;
    font-size: 0.75rem;
    word-break: break-all;
    resize: none;
  }
  .summary {
    padding: var(--sp-3);
  }
  .danger {
    color: var(--c-danger);
  }
</style>
