<script lang="ts">
  // Choose an instance icon: the default logo, a built-in pixel icon or an image file.
  import { open as openDialog } from "@tauri-apps/plugin-dialog";
  import InstanceIcon from "./InstanceIcon.svelte";
  import Icon from "./Icon.svelte";
  import { ICON_KEYS } from "../instanceIcons";
  import type { LoaderKind } from "../api";
  import { t } from "../i18n/index.svelte";

  let {
    icon = $bindable(null),
    file = $bindable(null),
    name,
    loader = "vanilla",
    id = "",
  }: {
    /** Built-in key or the instance's current `file:` icon. */
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
  {#each ICON_KEYS as key (key)}
    <button
      type="button"
      role="radio"
      aria-checked={icon === key && !file}
      title={t(`iconPicker.icons.${key}`)}
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
    grid-template-columns: repeat(auto-fill, minmax(2.9rem, 1fr));
    gap: 0.4rem;
  }
  button {
    display: grid;
    place-items: center;
    padding: 0.2rem;
    border: 2px solid transparent;
    border-radius: calc(var(--r-md) * 1.2);
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
