<script lang="ts">
  // Collapsed "Advanced" group: keeps rarely needed options out of the way without hiding that
  // they exist (the summary says what is inside).
  import type { Snippet } from "svelte";
  import Icon from "./Icon.svelte";
  import { t } from "../i18n/index.svelte";

  let {
    hint = "",
    open = $bindable(false),
    children,
  }: {
    /** What the group contains, shown next to the title while collapsed. */
    hint?: string;
    open?: boolean;
    children: Snippet;
  } = $props();
</script>

<details class="advanced" bind:open>
  <summary>
    <span class="chev"><Icon name="chevronRight" size={15} /></span>
    <span class="title">{t("common.advanced")}</span>
    {#if hint && !open}<span class="small faint truncate">{hint}</span>{/if}
  </summary>
  <div class="col gap-3 body">{@render children()}</div>
</details>

<style>
  .advanced {
    border-top: 1px solid var(--c-border);
    padding-top: var(--sp-2);
  }
  summary {
    display: flex;
    align-items: center;
    gap: var(--sp-2);
    min-width: 0;
    padding: 0.25rem 0;
    list-style: none;
    cursor: pointer;
    color: var(--c-text-muted);
    border-radius: var(--r-sm);
  }
  summary::-webkit-details-marker {
    display: none;
  }
  summary:hover,
  .advanced[open] summary {
    color: var(--c-text);
  }
  .title {
    font-weight: 600;
    flex: none;
  }
  .chev {
    display: inline-flex;
    transition: transform var(--dur) var(--ease);
  }
  .advanced[open] .chev {
    transform: rotate(90deg);
  }
  .body {
    margin-top: var(--sp-3);
  }
</style>
