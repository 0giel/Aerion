<script lang="ts">
  import { themes } from "../theme/engine.svelte";
  import { themeAssetUrl } from "../api";
  import Particles from "./Particles.svelte";
  import { ambient } from "../stores/ambient.svelte";

  const bg = $derived(themes.active.background);
  const motion = $derived(themes.active.motion.enabled && !themes.reducedMotion);
  // Animate only while someone is using the launcher (see stores/ambient).
  const animate = $derived(motion && ambient.active);
  const assetUrl = $derived(bg.asset ? themeAssetUrl(bg.asset) : null);

  let video: HTMLVideoElement | undefined = $state();
  // Don't decode video while nobody is looking (or when motion is off).
  $effect(() => {
    const v = video;
    if (!v) return;
    if (animate) void v.play().catch(() => {});
    else v.pause();
  });
</script>

<div class="background" aria-hidden="true">
  <div
    class="base"
    style:background-color={bg.color}
    style:background-image={bg.type === "gradient" && bg.gradient ? bg.gradient : undefined}
  ></div>
  {#if bg.type === "image" && assetUrl}
    <img class="media" src={assetUrl} alt="" style:filter={bg.blur ? `blur(${bg.blur}px)` : undefined} />
  {:else if bg.type === "video" && assetUrl}
    <video
      bind:this={video}
      class="media"
      src={assetUrl}
      muted
      loop
      playsinline
      style:filter={bg.blur ? `blur(${bg.blur}px)` : undefined}
    ></video>
  {/if}
  {#if bg.ribbons}
    <svg class="ribbons" viewBox="0 0 400 1000" preserveAspectRatio="none">
      <defs>
        <linearGradient id="rib" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stop-color="var(--c-text)" stop-opacity="0" />
          <stop offset="0.5" stop-color="var(--c-text)" stop-opacity="0.55" />
          <stop offset="1" stop-color="var(--c-text)" stop-opacity="0" />
        </linearGradient>
      </defs>
      <g class="r1"
        ><path
          d="M300 -50 C 200 200, 380 420, 260 640 S 300 900, 240 1080 L 300 1080 C 350 900, 330 760, 330 640 S 290 200, 360 -50 Z"
          fill="url(#rib)"
        /></g
      >
      <g class="r2"
        ><path
          d="M360 -50 C 280 180, 420 400, 320 620 S 370 880, 330 1080 L 380 1080 C 420 880, 390 740, 380 620 S 360 180, 410 -50 Z"
          fill="url(#rib)"
        /></g
      >
      <g class="r3"
        ><path
          d="M220 -50 C 150 260, 300 480, 200 700 S 250 920, 180 1080 L 215 1080 C 270 920, 240 780, 245 700 S 220 260, 260 -50 Z"
          fill="url(#rib)"
        /></g
      >
    </svg>
  {/if}
  {#if bg.particles !== "none"}
    <Particles kind={bg.particles} {animate} />
  {/if}
  <div id="fx-glow" class="fx-glow"></div>
  <div class="dim" style:opacity={bg.dim}></div>
  <div class="vignette"></div>
</div>

<style>
  .background {
    position: fixed;
    inset: 0;
    z-index: 0;
    overflow: hidden;
    pointer-events: none;
  }
  .base,
  .media,
  .dim,
  .vignette {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }
  .base {
    background-size: cover;
  }
  .media {
    object-fit: cover;
    transform: scale(1.04);
  }
  .dim {
    background: #000;
  }
  /* A soft light that follows the cursor and shows through the glass (see lib/fx.ts). */
  .fx-glow {
    position: absolute;
    left: 0;
    top: 0;
    width: 520px;
    height: 520px;
    border-radius: 50%;
    background: radial-gradient(
      circle,
      color-mix(in srgb, var(--c-accent) 22%, transparent),
      transparent 65%
    );
    opacity: 0;
    transition: opacity 400ms var(--ease);
  }
  :global([data-spring="off"]) .fx-glow {
    display: none;
  }
  .vignette {
    background: radial-gradient(
      120% 90% at 50% 40%,
      transparent 55%,
      rgb(0 0 0 / calc(var(--shadow-a) * 0.5)) 100%
    );
  }
  /* Static on purpose: a blurred, blended SVG repaints a third of the window per frame when
     WebKit has no GPU compositing (measured: ~45 % of a core). */
  .ribbons {
    position: absolute;
    right: -2%;
    top: 0;
    height: 100%;
    width: 42%;
    opacity: 0.11;
    filter: blur(14px);
    mix-blend-mode: screen;
  }
  :global([data-base="light"]) .ribbons {
    mix-blend-mode: multiply;
    opacity: 0.07;
  }
</style>
