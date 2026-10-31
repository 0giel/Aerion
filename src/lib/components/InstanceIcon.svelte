<script lang="ts">
  import Logo from "./Logo.svelte";
  import type { LoaderKind } from "../api";

  let {
    name,
    loader = "vanilla",
    size = 48,
  }: { name: string; loader?: LoaderKind; size?: number } = $props();

  // A stable hue per instance name, tinted towards the theme accent.
  const hue = $derived([...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7));
</script>

<div class="icon" style:width="{size}px" style:height="{size}px" style:--h={hue} data-loader={loader}>
  <span class="glyph"><Logo /></span>
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
