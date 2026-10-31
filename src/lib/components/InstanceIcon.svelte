<script lang="ts">
  import Logo from "./Logo.svelte";
  import { instanceIconUrl, type LoaderKind } from "../api";
  import { INSTANCE_ICONS, iconRects } from "../instanceIcons";

  let {
    name,
    loader = "vanilla",
    size = 48,
    icon = null,
    id = "",
  }: {
    name: string;
    loader?: LoaderKind;
    size?: number;
    /** Built-in key, `file:<name>` (needs `id`) or nothing for the launcher logo. */
    icon?: string | null;
    id?: string;
  } = $props();

  // A stable hue per instance name, tinted towards the theme accent.
  const hue = $derived([...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7));
  const file = $derived(id ? instanceIconUrl(id, icon) : undefined);
  const builtIn = $derived(icon && INSTANCE_ICONS[icon] ? iconRects(icon) : null);
  let broken = $state(false);
  $effect(() => {
    void file;
    broken = false;
  });
</script>

<div class="icon" style:width="{size}px" style:height="{size}px" style:--h={hue} data-loader={loader}>
  {#if file && !broken}
    <img src={file} alt="" onerror={() => (broken = true)} />
  {:else if builtIn}
    <svg class="pixels" viewBox="0 0 16 16" shape-rendering="crispEdges" aria-hidden="true">
      {#each builtIn as r, i (i)}
        <rect x={r.x} y={r.y} width={r.w} height="1" fill={r.fill} />
      {/each}
    </svg>
  {:else}
    <span class="glyph"><Logo /></span>
  {/if}
</div>

<style>
  .icon {
    flex: none;
    position: relative;
    display: grid;
    place-items: center;
    border-radius: calc(var(--r-md) * 1.1);
    background: linear-gradient(
      150deg,
      color-mix(in oklab, hsl(var(--h) 70% 60%) 55%, var(--c-accent)),
      color-mix(in oklab, hsl(calc(var(--h) + 40) 60% 32%) 70%, var(--c-bg))
    );
    color: #fff;
    box-shadow: inset 0 1px 0 rgb(255 255 255 / 0.18);
    overflow: hidden;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  .pixels {
    width: 72%;
    height: 72%;
    filter: drop-shadow(0 2px 2px rgb(0 0 0 / 0.3));
  }
  .glyph {
    width: 58%;
    height: 58%;
    display: grid;
    filter: drop-shadow(0 2px 3px rgb(0 0 0 / 0.25));
  }
  .glyph :global(svg) {
    width: 100%;
    height: 100%;
  }
</style>
