<script lang="ts">
  // CSS-3D player model built from the skin texture (no WebGL, no extra libraries).
  // Each body part is a cuboid whose six faces show the matching UV region of the skin.
  import { imgUrl } from "../api";
  import { themes } from "../theme/engine.svelte";

  let {
    skinUrl,
    slim = false,
    scale = 9,
  }: { skinUrl: string | null | undefined; slim?: boolean; scale?: number } = $props();

  type Vec3 = [number, number, number];
  interface Part {
    size: Vec3;
    pos: Vec3;
    uv: [number, number];
    overlay: [number, number] | null;
    inflate: number;
    mirror?: boolean;
  }

  let texHeight = $state(64);
  let loaded = $state(false);
  const url = $derived(imgUrl(skinUrl));

  $effect(() => {
    loaded = false;
    if (!url) return;
    const img = new Image();
    img.onload = () => {
      texHeight = img.naturalHeight === 32 ? 32 : 64;
      loaded = true;
    };
    img.src = url;
  });

  const parts = $derived.by((): Part[] => {
    const aw = slim ? 3 : 4;
    const legacy = texHeight === 32;
    return [
      { size: [8, 8, 8], pos: [0, -10, 0], uv: [0, 0], overlay: [32, 0], inflate: 0.5 },
      { size: [8, 12, 4], pos: [0, 0, 0], uv: [16, 16], overlay: legacy ? null : [16, 32], inflate: 0.25 },
      {
        size: [aw, 12, 4],
        pos: [-(4 + aw / 2), 0, 0],
        uv: [40, 16],
        overlay: legacy ? null : [40, 32],
        inflate: 0.25,
      },
      legacy
        ? {
            size: [aw, 12, 4],
            pos: [4 + aw / 2, 0, 0],
            uv: [40, 16],
            overlay: null,
            inflate: 0.25,
            mirror: true,
          }
        : { size: [aw, 12, 4], pos: [4 + aw / 2, 0, 0], uv: [32, 48], overlay: [48, 48], inflate: 0.25 },
      { size: [4, 12, 4], pos: [-2, 12, 0], uv: [0, 16], overlay: legacy ? null : [0, 32], inflate: 0.25 },
      legacy
        ? { size: [4, 12, 4], pos: [2, 12, 0], uv: [0, 16], overlay: null, inflate: 0.25, mirror: true }
        : { size: [4, 12, 4], pos: [2, 12, 0], uv: [16, 48], overlay: [0, 48], inflate: 0.25 },
    ];
  });

  interface Face {
    name: string;
    w: number;
    h: number;
    u: number;
    v: number;
    transform: string;
  }

  function faces([w, h, d]: Vec3, [u, v]: [number, number], s: number): Face[] {
    return [
      { name: "front", w, h, u: u + d, v: v + d, transform: `translateZ(${(d / 2) * s}px)` },
      {
        name: "back",
        w,
        h,
        u: u + d + w + d,
        v: v + d,
        transform: `rotateY(180deg) translateZ(${(d / 2) * s}px)`,
      },
      { name: "right", w: d, h, u, v: v + d, transform: `rotateY(-90deg) translateZ(${(w / 2) * s}px)` },
      {
        name: "left",
        w: d,
        h,
        u: u + d + w,
        v: v + d,
        transform: `rotateY(90deg) translateZ(${(w / 2) * s}px)`,
      },
      { name: "top", w, h: d, u: u + d, v, transform: `rotateX(90deg) translateZ(${(h / 2) * s}px)` },
      { name: "bottom", w, h: d, u: u + d + w, v, transform: `rotateX(-90deg) translateZ(${(h / 2) * s}px)` },
    ];
  }

  let yaw = $state(-24);
  let dragging = false;
  let lastX = 0;
  const animate = $derived(themes.active.motion.enabled && !themes.reducedMotion);
  let manual = $state(false);

  function down(e: PointerEvent) {
    dragging = true;
    manual = true;
    lastX = e.clientX;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  }
  function move(e: PointerEvent) {
    if (!dragging) return;
    yaw += (e.clientX - lastX) * 0.6;
    lastX = e.clientX;
  }
  function up() {
    dragging = false;
  }
</script>

<div
  class="viewer"
  role="img"
  aria-label="Player model"
  onpointerdown={down}
  onpointermove={move}
  onpointerup={up}
  onpointercancel={up}
  style:--s="{scale}px"
  style:height="{34 * scale}px"
  style:width="{20 * scale}px"
>
  <div class="model" class:spin={animate && !manual} style:--yaw="{yaw}deg">
    {#each parts as part, pi (pi)}
      <div
        class="part"
        style:transform="translate3d({part.pos[0] * scale}px, {part.pos[1] * scale}px, {part.pos[2] *
          scale}px)"
      >
        {#each [false, true] as outer (outer)}
          {#if !outer || part.overlay}
            {@const uv = outer && part.overlay ? part.overlay : part.uv}
            {@const kx = outer ? (part.size[0] + part.inflate * 2) / part.size[0] : 1}
            {@const ky = outer ? (part.size[1] + part.inflate * 2) / part.size[1] : 1}
            {@const kz = outer ? (part.size[2] + part.inflate * 2) / part.size[2] : 1}
            <div
              class="layer"
              class:outer
              style:transform="scale3d({kx}, {ky}, {kz}){part.mirror ? ' scaleX(-1)' : ''}"
            >
              {#each faces(part.size, uv, scale) as f (f.name)}
                <div
                  class="face"
                  class:blank={!loaded}
                  style:width="{f.w * scale}px"
                  style:height="{f.h * scale}px"
                  style:margin-left="{(-f.w * scale) / 2}px"
                  style:margin-top="{(-f.h * scale) / 2}px"
                  style:transform={f.transform}
                  style:background-image={loaded && url ? `url("${url}")` : undefined}
                  style:background-size="{64 * scale}px {texHeight * scale}px"
                  style:background-position="{-f.u * scale}px {-f.v * scale}px"
                ></div>
              {/each}
            </div>
          {/if}
        {/each}
      </div>
    {/each}
  </div>
</div>

<style>
  .viewer {
    position: relative;
    perspective: 1400px;
    cursor: grab;
    touch-action: none;
  }
  .viewer:active {
    cursor: grabbing;
  }
  .model {
    position: absolute;
    left: 50%;
    top: 42%;
    transform-style: preserve-3d;
    transform: rotateX(-12deg) rotateY(var(--yaw));
  }
  .model.spin {
    animation: idle 9s ease-in-out infinite alternate;
  }
  @keyframes idle {
    from {
      transform: rotateX(-12deg) rotateY(-32deg);
    }
    to {
      transform: rotateX(-10deg) rotateY(28deg);
    }
  }
  .part,
  .layer {
    position: absolute;
    transform-style: preserve-3d;
  }
  .face {
    position: absolute;
    left: 0;
    top: 0;
    image-rendering: pixelated;
    backface-visibility: hidden;
    background-repeat: no-repeat;
  }
  .face.blank {
    background: linear-gradient(160deg, var(--c-surface-3), var(--c-surface-2));
    border: 1px solid var(--c-border);
  }
  .outer .face.blank {
    display: none;
  }
</style>
