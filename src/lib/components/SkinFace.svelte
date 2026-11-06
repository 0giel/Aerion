<script lang="ts">
  // The player's face (8×8 + hat layer) cropped from the skin texture.
  import { imgUrl } from "../api";

  let { skinUrl, size = 32 }: { skinUrl: string | null | undefined; size?: number } = $props();
  let canvas: HTMLCanvasElement | undefined = $state();

  /** Old 64×32 skins often fill the hat layer with an opaque colour; the game hides it then. */
  function hatShown(img: HTMLImageElement): boolean {
    if (img.naturalHeight !== img.naturalWidth / 2) return true;
    try {
      const c = document.createElement("canvas");
      c.width = c.height = 8;
      const x = c.getContext("2d");
      if (!x) return false;
      x.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
      const px = x.getImageData(0, 0, 8, 8).data;
      for (let i = 3; i < px.length; i += 4) if (px[i] < 128) return true;
      return false;
    } catch {
      return false;
    }
  }

  $effect(() => {
    const c = canvas;
    const url = imgUrl(skinUrl);
    if (!c) return;
    const ctx = c.getContext("2d");
    if (!ctx) return;
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, 8, 8);
    if (!url) return;
    const img = new Image();
    img.onload = () => {
      ctx.drawImage(img, 8, 8, 8, 8, 0, 0, 8, 8);
      if (hatShown(img)) ctx.drawImage(img, 40, 8, 8, 8, 0, 0, 8, 8);
    };
    img.src = url;
  });
</script>

<span class="face" style:width="{size}px" style:height="{size}px">
  <canvas bind:this={canvas} width="8" height="8"></canvas>
</span>

<style>
  .face {
    flex: none;
    display: inline-block;
    border-radius: calc(var(--r-sm) * 0.8);
    overflow: hidden;
    background: linear-gradient(160deg, var(--c-surface-3), var(--c-surface-2));
  }
  canvas {
    width: 100%;
    height: 100%;
    image-rendering: pixelated;
  }
</style>
