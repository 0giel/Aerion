<script lang="ts">
  import Icon from "../../components/Icon.svelte";
  import { router } from "../../stores/router.svelte";
  import { ui } from "../../stores/ui.svelte";
  import { t } from "../../i18n/index.svelte";
  import type { WidgetProps } from "../widgets";

  // Widgets without options still take the shared props.
  const _props: WidgetProps = $props();
  const actions = [
    { key: "home.actions.newInstance", icon: "plus", run: () => (ui.newInstanceOpen = true) },
    { key: "home.actions.mods", icon: "package", run: () => router.go("mods") },
    { key: "nav.skins", icon: "shirt", run: () => router.go("skins") },
    { key: "nav.performance", icon: "cpu", run: () => router.go("performance") },
  ];
</script>

<div class="actions">
  {#each actions as a (a.key)}
    <button class="action" onclick={a.run}><Icon name={a.icon} size={18} /><span>{t(a.key)}</span></button>
  {/each}
</div>

<style>
  .actions {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(6rem, 1fr));
    gap: var(--sp-2);
    height: 100%;
    align-content: center;
  }
  .action {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 0.3rem;
    padding: 0.6rem 0.3rem;
    border: 1px solid var(--c-border);
    border-radius: var(--r-md);
    background: var(--c-surface-2);
    color: var(--c-text);
    cursor: pointer;
    font-size: 0.8rem;
  }
  .action:hover {
    border-color: color-mix(in srgb, var(--c-accent) 45%, var(--c-border));
  }
  .action :global(.icon) {
    color: var(--c-accent);
  }
</style>
