<script lang="ts">
  import { untrack } from "svelte";
  import { save as saveDialog } from "@tauri-apps/plugin-dialog";
  import Modal from "../../components/Modal.svelte";
  import { toasts } from "../../stores/toasts.svelte";
  import { commands, errorMessage, type Instance, type PackFormat } from "../../api";
  import { t } from "../../i18n/index.svelte";

  let { open = $bindable(false), instance }: { open?: boolean; instance: Instance } = $props();
  let format = $state<PackFormat>("mrpack");
  let name = $state(untrack(() => instance.name));
  let version = $state("1.0.0");
  let entries = $state.raw<string[]>([]);
  let include = $state<Record<string, boolean>>({});

  $effect(() => {
    if (!open) return;
    commands.listGameDir(instance.id).then((list) => {
      entries = list.filter((e) => !["mods", "resourcepacks", "shaderpacks", "datapacks"].includes(e));
      include = Object.fromEntries(
        entries.map((e) => [
          e,
          ["config", "options.txt", "kubejs", "defaultconfigs", "servers.dat"].includes(e),
        ]),
      );
    });
  });

  const ext: Record<PackFormat, string> = { mrpack: "mrpack", curseforge: "zip", luncher: "zip" };

  async function run() {
    const path = await saveDialog({
      defaultPath: `${name}.${ext[format]}`,
      filters: [{ name: format, extensions: [ext[format]] }],
    });
    if (!path) return;
    try {
      await commands.exportInstance(instance.id, path, {
        format,
        name,
        version,
        summary: null,
        include: Object.keys(include).filter((k) => include[k]),
      });
      open = false;
    } catch (e) {
      toasts.error(errorMessage(e));
    }
  }
</script>

<Modal bind:open title={t("instances.exportTitle", { name: instance.name })}>
  <div class="col gap-3">
    <div class="field">
      <span class="label">{t("instances.exportFormat")}</span>
      <div class="segmented" role="group">
        <button aria-pressed={format === "mrpack"} onclick={() => (format = "mrpack")}
          >Modrinth (.mrpack)</button
        >
        <button aria-pressed={format === "curseforge"} onclick={() => (format = "curseforge")}
          >CurseForge (.zip)</button
        >
        <button aria-pressed={format === "luncher"} onclick={() => (format = "luncher")}>Luncher</button>
      </div>
    </div>
    {#if format !== "luncher"}
      <div class="row gap-3">
        <div class="field grow">
          <label for="ex-name">{t("common.name")}</label><input
            id="ex-name"
            class="input"
            bind:value={name}
          />
        </div>
        <div class="field" style="width: 8rem">
          <label for="ex-ver">{t("common.version")}</label><input
            id="ex-ver"
            class="input"
            bind:value={version}
          />
        </div>
      </div>
      <div class="field">
        <span class="label">{t("instances.exportInclude")}</span>
        <div class="checks">
          {#each entries as e (e)}
            <label class="row small"
              ><input type="checkbox" bind:checked={include[e]} /><span class="mono truncate">{e}</span
              ></label
            >
          {/each}
        </div>
      </div>
    {/if}
  </div>
  {#snippet footer()}
    <button class="btn ghost" onclick={() => (open = false)}>{t("common.cancel")}</button>
    <button class="btn primary" onclick={run}>{t("common.export")}</button>
  {/snippet}
</Modal>

<style>
  .checks {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr));
    gap: 0.3rem 1rem;
    max-height: 12rem;
    overflow: auto;
  }
  .checks label {
    gap: 0.45rem;
    cursor: pointer;
  }
</style>
