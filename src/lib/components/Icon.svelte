<script lang="ts">
  import { ICONS } from "../icons";

  let {
    name,
    size = 18,
    stroke = 2,
    label,
  }: { name: string; size?: number; stroke?: number; label?: string } = $props();
</script>

<svg
  class="icon"
  width={size}
  height={size}
  viewBox="0 0 24 24"
  fill="none"
  stroke="currentColor"
  stroke-width={stroke}
  stroke-linecap="round"
  stroke-linejoin="round"
  role={label ? "img" : undefined}
  aria-label={label}
  aria-hidden={label ? undefined : "true"}
>
  <!-- eslint-disable-next-line svelte/no-at-html-tags -- static path data from icons.ts -->
  {@html ICONS[name] ?? ""}
</svg>

<style>
  .icon {
    flex: none;
    display: block;
  }
</style>
