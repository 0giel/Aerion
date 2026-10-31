<script lang="ts">
  // Player model drawn on a 2D canvas (no WebGL, no libraries, no CSS 3D — WebKitGTK without GPU
  // compositing flattens `preserve-3d`). Each body part is a cuboid; every face is an affine
  // image of its skin UV rectangle under an orthographic projection. Back faces are culled and
  // the rest painted far-to-near.
  import { imgUrl } from "../api";
  import { themes } from "../theme/engine.svelte";
  import { ambient, onAmbientFrame } from "../stores/ambient.svelte";

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
  interface Face {
    /** Skin texture rectangle. */
    tex: [number, number, number, number];
    /** Corner at texture (0,0) and the edges along texture x and y, in model units. */
    origin: Vec3;
    u: Vec3;
    v: Vec3;
    normal: Vec3;
  }

  const WIDTH = 20;
  const HEIGHT = 34;

  let canvas: HTMLCanvasElement | undefined = $state();
  let image = $state.raw<HTMLImageElement | null>(null);
  const url = $derived(imgUrl(skinUrl));

  $effect(() => {
    image = null;
    if (!url) return;
    const img = new Image();
    img.onload = () => (image = img);
    img.src = url;
    return () => (img.onload = null);
  });

  const parts = $derived.by((): Part[] => {
    const aw = slim ? 3 : 4;
    const legacy = image?.naturalHeight === 32;
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

  /** Faces of a cuboid centred at `pos` (y down, the player faces +z), Minecraft UV layout. */
  function cuboid(part: Part, outer: boolean): Face[] {
    const [w, h, d] = part.size;
    const [u0, v0] = outer && part.overlay ? part.overlay : part.uv;
    const k = outer ? part.inflate : 0;
    const [x, y, z] = [w / 2 + k, h / 2 + k, d / 2 + k];
    const [W, H, D] = [2 * x, 2 * y, 2 * z];
    const faces: Face[] = [
      { tex: [u0 + d, v0 + d, w, h], origin: [-x, -y, z], u: [W, 0, 0], v: [0, H, 0], normal: [0, 0, 1] },
      {
        tex: [u0 + 2 * d + w, v0 + d, w, h],
        origin: [x, -y, -z],
        u: [-W, 0, 0],
        v: [0, H, 0],
        normal: [0, 0, -1],
      },
      { tex: [u0, v0 + d, d, h], origin: [-x, -y, -z], u: [0, 0, D], v: [0, H, 0], normal: [-1, 0, 0] },
      { tex: [u0 + d + w, v0 + d, d, h], origin: [x, -y, z], u: [0, 0, -D], v: [0, H, 0], normal: [1, 0, 0] },
      { tex: [u0 + d, v0, w, d], origin: [-x, -y, -z], u: [W, 0, 0], v: [0, 0, D], normal: [0, -1, 0] },
      { tex: [u0 + d + w, v0, w, d], origin: [-x, y, z], u: [W, 0, 0], v: [0, 0, -D], normal: [0, 1, 0] },
    ];
    const [px, py, pz] = part.pos;
    const flip = (p: Vec3): Vec3 => (part.mirror ? [-p[0], p[1], p[2]] : p);
    return faces.map((f) => {
      const o = flip(f.origin);
      return {
        ...f,
        origin: [o[0] + px, o[1] + py, o[2] + pz],
        u: flip(f.u),
        v: flip(f.v),
        normal: flip(f.normal),
      };
    });
  }

  function draw(yawDeg: number, pitchDeg: number) {
    const c = canvas;
    const ctx = c?.getContext("2d");
    if (!c || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = WIDTH * scale;
    const cssH = HEIGHT * scale;
    if (c.width !== Math.round(cssW * dpr)) c.width = Math.round(cssW * dpr);
    if (c.height !== Math.round(cssH * dpr)) c.height = Math.round(cssH * dpr);

    const [sy, cy] = [Math.sin((yawDeg * Math.PI) / 180), Math.cos((yawDeg * Math.PI) / 180)];
    const [sp, cp] = [Math.sin((pitchDeg * Math.PI) / 180), Math.cos((pitchDeg * Math.PI) / 180)];
    // Rotate around Y (yaw), then X (pitch). Directions use `rot`; points are taken relative to
    // the model centre (y = 2) first.
    const rot = ([x, y, z]: Vec3): Vec3 => {
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      return [x1, y * cp - z1 * sp, y * sp + z1 * cp];
    };
    const point = ([x, y, z]: Vec3): Vec3 => rot([x, y - 2, z]);
    const s = scale * dpr;
    const ox = (cssW / 2) * dpr;
    const oy = (cssH / 2) * dpr;

    const visible: { face: Face; depth: number; outer: boolean }[] = [];
    for (const part of parts) {
      for (const outer of [false, true]) {
        if (outer && (!part.overlay || !image)) continue;
        for (const face of cuboid(part, outer)) {
          if (rot(face.normal)[2] <= 1e-6) continue;
          const o = face.origin;
          const centre: Vec3 = [
            o[0] + (face.u[0] + face.v[0]) / 2,
            o[1] + (face.u[1] + face.v[1]) / 2,
            o[2] + (face.u[2] + face.v[2]) / 2,
          ];
          visible.push({ face, depth: point(centre)[2] + (outer ? 0.01 : 0), outer });
        }
      }
    }
    visible.sort((a, b) => a.depth - b.depth);

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    ctx.imageSmoothingEnabled = false;
    const blank = image ? "" : getComputedStyle(c).getPropertyValue("--c-surface-3").trim() || "#444";
    for (const { face } of visible) {
      const [tu, tv, tw, th] = face.tex;
      const o = point(face.origin);
      const u = rot(face.u);
      const v = rot(face.v);
      // Texture pixel (a, b) → origin + a/tw·u + b/th·v, projected orthographically.
      ctx.setTransform(
        (u[0] / tw) * s,
        (u[1] / tw) * s,
        (v[0] / th) * s,
        (v[1] / th) * s,
        ox + o[0] * s,
        oy + o[1] * s,
      );
      if (image) {
        // A hair of overlap hides seams between neighbouring faces.
        ctx.drawImage(image, tu, tv, tw, th, -0.02, -0.02, tw + 0.04, th + 0.04);
      } else {
        ctx.fillStyle = blank;
        ctx.fillRect(0, 0, tw, th);
      }
    }
  }

  let yaw = $state(-24);
  let manual = $state(false);
  let dragging = false;
  let lastX = 0;
  const motion = $derived(themes.active.motion.enabled && !themes.reducedMotion && !manual);
  const animate = $derived(motion && ambient.active);
  // Sway clock in ms; it only advances while animating, so pausing and resuming doesn't jump.
  let clock = 0;
  const sway = () => {
    const k = (1 - Math.cos((Math.PI * clock) / 9000)) / 2;
    return [-32 + 60 * k, -12 + 2 * k] as const;
  };

  $effect(() => {
    // Track everything `draw` reads so a static model redraws on change.
    void [parts, image, scale, canvas];
    if (!animate) {
      if (manual) draw(yaw, -12);
      else draw(...sway());
      return;
    }
    // Sway between -32° and 28° (9 s each way) on the shared ambient clock.
    return onAmbientFrame((dt) => {
      clock += dt;
      draw(...sway());
    });
  });

  function down(e: PointerEvent) {
    dragging = true;
    if (!manual) yaw = sway()[0];
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
>
  <canvas bind:this={canvas} style:width="{WIDTH * scale}px" style:height="{HEIGHT * scale}px"></canvas>
</div>

<style>
  .viewer {
    cursor: grab;
    touch-action: none;
  }
  canvas {
    display: block;
  }
  .viewer:active {
    cursor: grabbing;
  }
</style>
