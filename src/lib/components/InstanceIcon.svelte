<script lang="ts">
  import { instanceIconUrl, textureUrl, type LoaderKind } from "../api";
  import { iconTexture } from "../instanceIcons";

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
    /** `mc:<texture>`, `file:<name>` (needs `id`) or nothing for the launcher logo. */
    icon?: string | null;
    id?: string;
  } = $props();

  // A stable hue per instance name, tinted towards the theme accent.
  const hue = $derived([...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7));
  // Instances without a chosen icon get their own mark: a 5×5 pattern of blocks made from the
  // name, mirrored left to right, so no two instances look the same.
  const cells = $derived.by(() => {
    let h = 2166136261;
    for (const c of name) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
    const out: { x: number; y: number }[] = [];
    for (let y = 0; y < 5; y++)
      for (let x = 0; x < 3; x++) {
        h = Math.imul(h ^ (h >>> 15), 2246822519) + 0x9e3779b9;
        if (((h >>> 9) & 7) < 4) continue;
        out.push({ x, y });
        if (x < 2) out.push({ x: 4 - x, y });
      }
    return out.length >= 4 ? out : [...out, { x: 2, y: 2 }, { x: 1, y: 2 }, { x: 3, y: 2 }, { x: 2, y: 1 }];
  });
  const file = $derived(id ? instanceIconUrl(id, icon) : undefined);
  const texture = $derived(iconTexture(icon));
  // A missing image (no client jar yet, offline) falls back to the logo.
  let broken = $state(false);
  $effect(() => {
    void [file, texture];
    broken = false;
  });
</script>

<div
  class="icon"
  style:width="{size}px"
  style:height="{size}px"
  style:--h={hue}
  style:--size="{size}px"
  data-loader={loader}
>
  {#if file && !broken}
    <img src={file} alt="" onerror={() => (broken = true)} />
  {:else if texture && !broken}
    <img class="texture" src={textureUrl(texture)} alt="" onerror={() => (broken = true)} />
  {:else}
    <svg class="glyph" viewBox="0 0 5 5" shape-rendering="crispEdges" aria-hidden="true">
      {#each cells as c (c.x + "," + c.y)}<rect x={c.x} y={c.y} width="1.02" height="1.02" />{/each}
    </svg>
  {/if}
</div>

<style>
  .icon {
    flex: none;
    position: relative;
    display: grid;
    place-items: center;
    border-radius: var(--r-sm);
    background: color-mix(in oklab, hsl(var(--h) 62% 50%) 62%, var(--c-accent));
    color: #fff;
    /* Lit from above with a darker lower edge, like the blocks elsewhere in the launcher. */
    box-shadow:
      inset 0 1px 0 rgb(255 255 255 / 0.22),
      inset 0 calc(-0.07 * var(--size)) 0 rgb(0 0 0 / 0.22);
    overflow: hidden;
  }
  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }
  img.texture {
    width: 68%;
    height: 68%;
    object-fit: contain;
    /* Older WebKit ignores `pixelated` and keeps the first, crisp fallback. */
    image-rendering: -webkit-optimize-contrast;
    image-rendering: pixelated;
    filter: drop-shadow(0 2px 2px rgb(0 0 0 / 0.35));
  }
  .glyph {
    width: 56%;
    height: 56%;
    margin-top: -4%;
    fill: currentColor;
    filter: drop-shadow(0 1px 0 rgb(0 0 0 / 0.25));
  }
</style>
