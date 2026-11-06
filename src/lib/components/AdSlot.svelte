<script lang="ts">
  // One place for an ad (see lib/ads.ts). Empty, it renders nothing at all.
  import { openUrl } from "@tauri-apps/plugin-opener";
  import { adProvider, loadAd, type Ad, type AdSlotId } from "../ads";
  import { imgUrl } from "../api";
  import { t } from "../i18n/index.svelte";

  let { slot }: { slot: AdSlotId } = $props();
  let ad = $state.raw<Ad | null>(null);

  $effect(() => {
    let live = true;
    void loadAd(slot).then((a) => {
      if (!live) return;
      ad = a;
      if (a) adProvider().impression?.(a, slot);
    });
    return () => (live = false);
  });

  function open() {
    if (!ad) return;
    adProvider().click?.(ad, slot);
    void openUrl(ad.url);
  }
</script>

{#if ad}
  <button class="ad card" onclick={open} title={ad.url}>
    {#if ad.image}<img src={imgUrl(ad.image)} alt="" loading="lazy" />{/if}
    <span class="col gap-1 grow" style="min-width:0">
      <span class="tiny faint">{t("ads.label")}{ad.sponsor ? ` · ${ad.sponsor}` : ""}</span>
      <span class="strong truncate">{ad.title}</span>
      {#if ad.text}<span class="small muted clamp">{ad.text}</span>{/if}
    </span>
  </button>
{/if}

<style>
  .ad {
    display: flex;
    gap: var(--sp-3);
    align-items: center;
    width: 100%;
    padding: var(--sp-3);
    text-align: left;
    cursor: pointer;
    transition: border-color var(--dur) var(--ease);
  }
  .ad:hover {
    border-color: var(--c-accent);
  }
  img {
    width: 3.5rem;
    height: 3.5rem;
    flex: none;
    object-fit: cover;
    border-radius: var(--r-sm);
  }
  .clamp {
    display: -webkit-box;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
</style>
