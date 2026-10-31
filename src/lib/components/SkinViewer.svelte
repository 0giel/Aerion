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
    /** Turns on the neck towards the cursor. */
    head?: boolean;
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
      { size: [8, 8, 8], pos: [0, -10, 0], uv: [0, 0], overlay: [32, 0], inflate: 0.5, head: true },
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

  const NECK: Vec3 = [0, -6, 0];
  const rad = (deg: number) => (deg * Math.PI) / 180;

  /** Body yaw/pitch of the view, plus the head's own turn relative to the body. */
  function draw(yawDeg: number, pitchDeg: number, headYaw = 0, headPitch = 0) {
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
    // The head turns in model space around the neck before the view rotation.
    const [hsy, hcy, hsp, hcp] = [
      Math.sin(rad(headYaw)),
      Math.cos(rad(headYaw)),
      Math.sin(rad(headPitch)),
      Math.cos(rad(headPitch)),
    ];
    // Nod around the head's own x axis first, then turn: a neck, not a tilted turntable.
    const turn = ([x, y, z]: Vec3): Vec3 => {
      const y1 = y * hcp - z * hsp;
      const z1 = y * hsp + z * hcp;
      return [x * hcy + z1 * hsy, y1, -x * hsy + z1 * hcy];
    };
    const turnPoint = (p: Vec3): Vec3 => {
      const [x, y, z] = turn([p[0] - NECK[0], p[1] - NECK[1], p[2] - NECK[2]]);
      return [x + NECK[0], y + NECK[1], z + NECK[2]];
    };
    const posed = (part: Part, f: Face): Face =>
      part.head && (headYaw || headPitch)
        ? { ...f, origin: turnPoint(f.origin), u: turn(f.u), v: turn(f.v), normal: turn(f.normal) }
        : f;
    const s = scale * dpr;
    const ox = (cssW / 2) * dpr;
    const oy = (cssH / 2) * dpr;

    const visible: { face: Face; depth: number; outer: boolean }[] = [];
    for (const part of parts) {
      for (const outer of [false, true]) {
        if (outer && (!part.overlay || !image)) continue;
        for (const raw of cuboid(part, outer)) {
          const face = posed(part, raw);
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
  const motion = $derived(themes.active.motion.enabled && !themes.reducedMotion);
  const animate = $derived(motion && ambient.active);
  // Sway clock in ms; it only advances while animating, so pausing and resuming doesn't jump.
  let clock = 0;
  const PITCH = -12;
  const sway = () => {
    const k = (1 - Math.cos((Math.PI * clock) / 9000)) / 2;
    return [-32 + 60 * k, PITCH + 2 * k] as const;
  };
  const body = () => (manual ? ([yaw, PITCH] as const) : sway());

  // Head tracking: where the head should look (null = straight ahead) and its current turn.
  // The target is computed on pointer moves, not per frame, so frames never force a layout.
  let aim: { x: number; y: number } | null = null;
  let head = { yaw: 0, pitch: 0 };
  /** Distance (CSS px) of the virtual eye plane: smaller = the head turns sooner. */
  const DEPTH = 420;
  /** Easing time constant: the head covers ~95 % of a turn in 3× this. */
  const EASE_MS = 45;
  /** While the head turns, the model redraws on every display frame (measured ~41 fps here,
   *  +6 % CPU) instead of the 20 fps ambient clock; it falls back once the head has settled. */
  const TRACK_MS = 15;
  const clamp = (v: number, lim: number) => Math.max(-lim, Math.min(lim, v));

  function headTarget(bodyYaw: number, bodyPitch: number) {
    if (!aim) return { yaw: 0, pitch: 0 };
    // Facing the cursor means undoing the body's own yaw and the view pitch.
    return { yaw: clamp(aim.x - bodyYaw, 60), pitch: clamp(aim.y - bodyPitch, 35) };
  }

  /** Advance sway and head easing by `dt` ms and draw. Returns whether the head still moves. */
  function step(dt: number): boolean {
    if (!manual) clock += dt;
    const [by, bp] = body();
    const target = headTarget(by, bp);
    const k = 1 - Math.exp(-dt / EASE_MS);
    head = {
      yaw: head.yaw + (target.yaw - head.yaw) * k,
      pitch: head.pitch + (target.pitch - head.pitch) * k,
    };
    draw(by, bp, head.yaw, head.pitch);
    return Math.abs(target.yaw - head.yaw) + Math.abs(target.pitch - head.pitch) > 0.3;
  }

  // Fast loop while the head is turning; the ambient clock covers the slow sway otherwise.
  let trackRaf = 0;
  let lastStep = 0;
  function track(now: number) {
    trackRaf = 0;
    if (!animate) return;
    let moving = true;
    if (now - lastStep >= TRACK_MS) {
      moving = step(Math.min(100, now - lastStep));
      lastStep = now;
    }
    if (moving) trackRaf = requestAnimationFrame(track);
  }

  $effect(() => {
    if (!motion) return;
    const move = (e: PointerEvent) => {
      const c = canvas;
      if (!c) return;
      const r = c.getBoundingClientRect();
      // The head's centre is 12 model units above the canvas centre.
      const hx = r.left + r.width / 2;
      const hy = r.top + r.height / 2 - 12 * scale;
      aim = {
        x: (Math.atan2(e.clientX - hx, DEPTH) * 180) / Math.PI,
        y: (Math.atan2(hy - e.clientY, DEPTH) * 180) / Math.PI,
      };
      if (!trackRaf && animate) {
        lastStep = performance.now();
        trackRaf = requestAnimationFrame(track);
      }
    };
    const out = (e: PointerEvent) => {
      if (e.relatedTarget) return;
      aim = null;
      if (!trackRaf && animate) {
        lastStep = performance.now();
        trackRaf = requestAnimationFrame(track);
      }
    };
    window.addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerout", out, { passive: true });
    return () => {
      window.removeEventListener("pointermove", move);
      document.removeEventListener("pointerout", out);
      cancelAnimationFrame(trackRaf);
      trackRaf = 0;
      aim = null;
      head = { yaw: 0, pitch: 0 };
    };
  });

  $effect(() => {
    // Track everything `draw` reads so a static model redraws on change.
    void [parts, image, scale, canvas];
    if (!animate) {
      // Reads `manual` and `yaw`, so dragging redraws a static model too.
      draw(...body(), head.yaw, head.pitch);
      return;
    }
    // Sway between -32° and 28° (9 s each way) on the shared ambient clock, unless the fast
    // tracking loop is already drawing.
    return onAmbientFrame((dt) => {
      if (trackRaf) return;
      step(dt);
      lastStep = performance.now();
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
