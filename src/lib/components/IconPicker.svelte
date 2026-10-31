<script lang="ts">
  // Choose an instance icon: the default logo, a built-in pixel icon or an image file.
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import InstanceIcon from "./InstanceIcon.svelte";
  import Icon from "./Icon.svelte";
  import { MC_ICONS, iconLabelKey, iconTexture } from "../instanceIcons";
  import type { LoaderKind } from "../api";
  import { t } from "../i18n/index.svelte";

  let {
    icon = $bindable(null),
    file = $bindable(null),
    name,
    loader = "vanilla",
    id = "",
  }: {
    /** `mc:<texture>` or the instance's current `file:` icon. */
    icon?: string | null;
    /** A newly picked image on disk (imported when saving). */
    file?: string | null;
    name: string;
    loader?: LoaderKind;
    id?: string;
  } = $props();

  const custom = $derived(!!file || !!icon?.startsWith("file:"));

  async function pickFile() {
    const path = await openDialog({
      multiple: false,
      filters: [{ name: t("iconPicker.images"), extensions: ["png", "jpg", "jpeg", "webp", "gif"] }],
    });
    if (typeof path === "string") file = path;
  }

  function choose(key: string | null) {
    icon = key;
    file = null;
  }
</script>

<div class="picker" role="radiogroup" aria-label={t("iconPicker.label")}>
  <button
    type="button"
    role="radio"
    aria-checked={!icon && !file}
    title={t("iconPicker.default")}
    onclick={() => choose(null)}><InstanceIcon {name} {loader} size={38} /></button
  >
  {#each MC_ICONS as key (key)}
    <button
      type="button"
      role="radio"
      aria-checked={!file && iconTexture(icon) === iconTexture(key)}
      title={t(iconLabelKey(key))}
      onclick={() => choose(key)}><InstanceIcon {name} {loader} icon={key} size={38} /></button
    >
  {/each}
  <button
    type="button"
    role="radio"
    aria-checked={custom}
    class="custom"
    title={t("iconPicker.custom")}
    onclick={pickFile}
  >
    {#if icon?.startsWith("file:") && !file}
      <InstanceIcon {name} {loader} {icon} {id} size={38} />
    {:else}
      <span class="upload"><Icon name="image" size={18} /></span>
    {/if}
  </button>
</div>
{#if file}
  <span class="small muted truncate file"
    >{t("iconPicker.chosen", { file: file.split(/[\\/]/).pop() ?? "" })}</span
  >
{/if}

<style>
  .picker {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(48px, 1fr));
    gap: 0.3rem;
    max-height: 15.5rem;
    overflow-y: auto;
    padding: 2px;
  }
  /* The ring hugs the 38 px icon: 3 px gap + 2 px border, radius grown by the same 5 px. */
  button {
    justify-self: center;
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    padding: 3px;
    line-height: 0;
    border: 2px solid transparent;
    border-radius: calc(var(--r-md) * 1.1 + 5px);
    background: none;
    cursor: pointer;
    transition: border-color var(--dur) var(--ease);
  }
  button:hover {
    border-color: var(--c-border);
  }
  button[aria-checked="true"] {
    border-color: var(--c-accent);
  }
  .upload {
    width: 38px;
    height: 38px;
    display: grid;
    place-items: center;
    border-radius: var(--r-md);
    border: 1px dashed var(--c-border);
    color: var(--c-text-muted);
  }
  .file {
    display: block;
    margin-top: 0.35rem;
  }
</style>
