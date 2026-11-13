<script lang="ts">
  // Player model drawn on a 2D canvas (no WebGL, no libraries, no CSS 3D — WebKitGTK without GPU
  // compositing flattens `preserve-3d`). Each body part is a cuboid; every face is an affine
  // image of its skin UV rectangle under an orthographic projection. Back faces are culled and
  // the rest painted far-to-near. Faces are shaded by pre-darkened copies of the texture, so a
  // frame costs the same as unshaded drawing.
  import { imgUrl } from "../api";
  import { themes } from "../theme/engine.svelte";
  import { ambient, onAmbientFrame } from "../stores/ambient.svelte";

  let {
    skinUrl,
    capeUrl = null,
    slim = false,
    scale = 9,
    follow = true,
    pitch = -12,
    ground = true,
  }: {
    skinUrl: string | null | undefined;
    capeUrl?: string | null;
    slim?: boolean;
    scale?: number;
    /** Turn the head towards the cursor (a response to the user's own input, so it also works
     *  with reduced motion — then without easing). */
    follow?: boolean;
    /** View tilt in degrees (negative looks down on the player). */
    pitch?: number;
    /** Draw the soft shadow under the feet (off when the player stands on something drawn). */
    ground?: boolean;
  } = $props();

  type Vec3 = [number, number, number];
  type Kind = "head" | "body" | "armR" | "armL" | "legR" | "legL" | "cape";
  interface Part {
    kind: Kind;
    size: Vec3;
    pos: Vec3;
    uv: [number, number];
    overlay: [number, number] | null;
    inflate: number;
    mirror?: boolean;
    /** Turned 180° (the cape's outer side faces backwards). */
    back?: boolean;
  }
  interface Face {
    /** Texture rectangle (in 64-px units). */
    tex: [number, number, number, number];
    /** Corner at texture (0,0) and the edges along texture x and y, in model units. */
    origin: Vec3;
    u: Vec3;
    v: Vec3;
    normal: Vec3;
  }

  const WIDTH = 22;
  const HEIGHT = 36;
  /** Shading levels: multiplied brightness of each pre-darkened texture copy. */
  const SHADES = [1, 0.9, 0.8, 0.7, 0.6];
  /** Light from the upper left front (y points down). */
  const LIGHT: Vec3 = (() => {
    const l: Vec3 = [-0.42, -0.55, 0.72];
    const n = Math.hypot(...l);
    return [l[0] / n, l[1] / n, l[2] / n];
  })();

  let canvas: HTMLCanvasElement | undefined = $state();
  let skin = $state.raw<HTMLCanvasElement[] | null>(null);
  let cape = $state.raw<HTMLCanvasElement[] | null>(null);
  let legacy = $state(false);

  /** Copies of an image darkened to each shade (only opaque pixels: `source-atop`). */
  function shaded(img: HTMLImageElement): HTMLCanvasElement[] {
    return SHADES.map((b) => {
      const c = document.createElement("canvas");
      c.width = img.naturalWidth;
      c.height = img.naturalHeight;
      const x = c.getContext("2d")!;
      x.drawImage(img, 0, 0);
      if (b < 1) {
        x.globalCompositeOperation = "source-atop";
        x.fillStyle = `rgba(0,0,0,${(1 - b).toFixed(2)})`;
        x.fillRect(0, 0, c.width, c.height);
      }
      return c;
    });
  }

  function load(
    url: string | undefined,
    set: (v: HTMLCanvasElement[] | null, img?: HTMLImageElement) => void,
  ) {
    set(null);
    if (!url) return;
    const img = new Image();
    img.onload = () => set(shaded(img), img);
    img.src = url;
    return () => (img.onload = null);
  }
  $effect(() =>
    load(imgUrl(skinUrl), (v, img) => {
      skin = v;
      if (img) legacy = img.naturalHeight * 2 === img.naturalWidth;
    }),
  );
  $effect(() => load(imgUrl(capeUrl), (v) => (cape = v)));

  const parts = $derived.by((): Part[] => {
    const aw = slim ? 3 : 4;
    const list: Part[] = [
      { kind: "head", size: [8, 8, 8], pos: [0, -10, 0], uv: [0, 0], overlay: [32, 0], inflate: 0.5 },
      {
        kind: "body",
        size: [8, 12, 4],
        pos: [0, 0, 0],
        uv: [16, 16],
        overlay: legacy ? null : [16, 32],
        inflate: 0.25,
      },
      {
        kind: "armR",
        size: [aw, 12, 4],
        pos: [-(4 + aw / 2), 0, 0],
        uv: [40, 16],
        overlay: legacy ? null : [40, 32],
        inflate: 0.25,
      },
      legacy
        ? {
            kind: "armL",
            size: [aw, 12, 4],
            pos: [4 + aw / 2, 0, 0],
            uv: [40, 16],
            overlay: null,
            inflate: 0.25,
            mirror: true,
          }
        : {
            kind: "armL",
            size: [aw, 12, 4],
            pos: [4 + aw / 2, 0, 0],
            uv: [32, 48],
            overlay: [48, 48],
            inflate: 0.25,
          },
      {
        kind: "legR",
        size: [4, 12, 4],
        pos: [-2, 12, 0],
        uv: [0, 16],
        overlay: legacy ? null : [0, 32],
        inflate: 0.25,
      },
      legacy
        ? {
            kind: "legL",
            size: [4, 12, 4],
            pos: [2, 12, 0],
            uv: [0, 16],
            overlay: null,
            inflate: 0.25,
            mirror: true,
          }
        : { kind: "legL", size: [4, 12, 4], pos: [2, 12, 0], uv: [16, 48], overlay: [0, 48], inflate: 0.25 },
    ];
    if (cape)
      list.push({
        kind: "cape",
        size: [10, 16, 1],
        pos: [0, 2, -2.6],
        uv: [0, 0],
        overlay: null,
        inflate: 0,
        back: true,
      });
    return list;
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
    // Mirror: legacy skins reuse the right limb's texture for the left. Back: 180° about y.
    const flip = (p: Vec3): Vec3 =>
      part.back ? [-p[0], p[1], -p[2]] : part.mirror ? [-p[0], p[1], p[2]] : p;
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

  // Geometry only changes with the skin layout, not per frame.
  const geometry = $derived(
    parts.flatMap((part) =>
      [false, true]
        .filter((outer) => !outer || part.overlay)
        .map((outer) => ({ part, outer, faces: cuboid(part, outer) })),
    ),
  );

  const rad = (deg: number) => (deg * Math.PI) / 180;
  /** Rotation: roll (about z), then pitch (about x, + tilts the front up), then yaw (about y). */
  function rotator(yawDeg: number, pitchDeg: number, rollDeg = 0) {
    const [sy, cy] = [Math.sin(rad(yawDeg)), Math.cos(rad(yawDeg))];
    const [sp, cp] = [Math.sin(rad(pitchDeg)), Math.cos(rad(pitchDeg))];
    const [sr, cr] = [Math.sin(rad(rollDeg)), Math.cos(rad(rollDeg))];
    return ([x, y, z]: Vec3): Vec3 => {
      const x0 = x * cr - y * sr;
      const y0 = x * sr + y * cr;
      const y1 = y0 * cp - z * sp;
      const z1 = y0 * sp + z * cp;
      return [x0 * cy + z1 * sy, y1, -x0 * sy + z1 * cy];
    };
  }

  /** Camera: the model turns (yaw) first, then the view tilts (pitch) about the screen's x axis,
   *  so the player stays upright from every side. */
  function viewer(yawDeg: number, pitchDeg: number) {
    const [sy, cy] = [Math.sin(rad(yawDeg)), Math.cos(rad(yawDeg))];
    const [sp, cp] = [Math.sin(rad(pitchDeg)), Math.cos(rad(pitchDeg))];
    return ([x, y, z]: Vec3): Vec3 => {
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      return [x1, y * cp - z1 * sp, y * sp + z1 * cp];
    };
  }

  interface Pose {
    pivot: Vec3;
    turn: (v: Vec3) => Vec3;
  }

  /** Limb poses: the head looks around, arms sway like in game, the cape hangs back. */
  function poses(headYaw: number, headPitch: number, t: number): Partial<Record<Kind, Pose>> {
    const arm = 2.5 + 2 * Math.cos(t * 0.0018);
    const swing = 3 * Math.sin(t * 0.0013);
    const capeTilt = -(7 + 2.5 * Math.sin(t * 0.0011));
    const aw = slim ? 3 : 4;
    return {
      head: { pivot: [0, -6, 0], turn: rotator(headYaw, headPitch) },
      armR: { pivot: [-(4 + aw / 2), -4, 0], turn: rotator(0, swing, arm) },
      armL: { pivot: [4 + aw / 2, -4, 0], turn: rotator(0, -swing, -arm) },
      cape: { pivot: [0, -6, -2.1], turn: rotator(0, capeTilt) },
    };
  }

  function draw(yawDeg: number, pitchDeg: number, headYaw = 0, headPitch = 0, t = 0) {
    const c = canvas;
    const ctx = c?.getContext("2d");
    if (!c || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const cssW = WIDTH * scale;
    const cssH = HEIGHT * scale;
    if (c.width !== Math.round(cssW * dpr)) c.width = Math.round(cssW * dpr);
    if (c.height !== Math.round(cssH * dpr)) c.height = Math.round(cssH * dpr);

    const rot = viewer(yawDeg, pitchDeg);
    // Points are taken relative to the model centre (y = 2) first.
    const point = ([x, y, z]: Vec3): Vec3 => rot([x, y - 2, z]);
    const s = scale * dpr;
    const ox = (cssW / 2) * dpr;
    const oy = (cssH / 2) * dpr;
    const pose = poses(headYaw, headPitch, t);

    const visible: { face: Face; depth: number; shade: number; kind: Kind }[] = [];
    for (const { part, outer, faces } of geometry) {
      const p = pose[part.kind];
      for (const raw of faces) {
        let face = raw;
        if (p) {
          const at = (v: Vec3): Vec3 => {
            const [x, y, z] = p.turn([v[0] - p.pivot[0], v[1] - p.pivot[1], v[2] - p.pivot[2]]);
            return [x + p.pivot[0], y + p.pivot[1], z + p.pivot[2]];
          };
          face = {
            ...raw,
            origin: at(raw.origin),
            u: p.turn(raw.u),
            v: p.turn(raw.v),
            normal: p.turn(raw.normal),
          };
        }
        const n = rot(face.normal);
        if (n[2] <= 1e-6) continue;
        const o = face.origin;
        const centre: Vec3 = [
          o[0] + (face.u[0] + face.v[0]) / 2,
          o[1] + (face.u[1] + face.v[1]) / 2,
          o[2] + (face.u[2] + face.v[2]) / 2,
        ];
        const light = 0.6 + 0.4 * Math.max(0, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
        const shade = SHADES.reduce(
          (best, b, i) => (Math.abs(b - light) < Math.abs(SHADES[best] - light) ? i : best),
          0,
        );
        visible.push({
          face,
          depth: point(centre)[2] + (outer ? 0.01 : 0),
          shade,
          kind: part.kind,
        });
      }
    }
    // Face centres can't order the large cape against the legs, but its place is known: it hangs
    // behind the body, so it is painted first when the front is in view, and from behind over
    // the body and limbs (still under the head, which overhangs it from above).
    const fromBehind = rot([0, 0, -1])[2] > 0;
    const layer = (k: Kind) => (k === "cape" ? (fromBehind ? 1 : -1) : fromBehind && k === "head" ? 2 : 0);
    visible.sort((a, b) => layer(a.kind) - layer(b.kind) || a.depth - b.depth);

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    ctx.imageSmoothingEnabled = false;

    // Soft contact shadow under the feet; on drawn ground it is wider and darker, to seat the
    // player on it.
    const [fx, fy] = point([0, 18, 0]);
    const rx = (ground ? 7 : 8.5) * s;
    ctx.setTransform(1, 0, 0, ground ? 0.24 : Math.sin(rad(-pitch)), ox + fx * s, oy + fy * s);
    const g = ctx.createRadialGradient(0, 0, 0, 0, 0, rx);
    g.addColorStop(0, ground ? "rgba(0,0,0,0.38)" : "rgba(0,0,0,0.5)");
    if (!ground) g.addColorStop(0.55, "rgba(0,0,0,0.3)");
    g.addColorStop(1, "rgba(0,0,0,0)");
    ctx.fillStyle = g;
    ctx.fillRect(-rx, -rx, 2 * rx, 2 * rx);

    const blank = skin ? "" : getComputedStyle(c).getPropertyValue("--c-surface-3").trim() || "#444";
    for (const { face, shade, kind } of visible) {
      const tex = kind === "cape" ? cape : skin;
      // HD textures: scale texture coordinates from the 64-px layout.
      const k = tex ? tex[0].width / 64 : 1;
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
      if (tex) {
        // A hair of overlap hides seams between neighbouring faces.
        ctx.drawImage(tex[shade], tu * k, tv * k, tw * k, th * k, -0.02, -0.02, tw + 0.04, th + 0.04);
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
  const animate = $derived(motion && ambient.active && !themes.lite);
  // Sway clock in ms; it only advances while animating, so pausing and resuming doesn't jump.
  let clock = 0;
  const PITCH = $derived(pitch);
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
    // Facing the cursor means undoing the body's own yaw and the view pitch. A model turned away
    // (dragged round) can't look at the viewer; its head stays straight instead of twisting.
    const yaw = ((((aim.x - bodyYaw) % 360) + 540) % 360) - 180;
    if (Math.abs(yaw) > 90) return { yaw: 0, pitch: 0 };
    return { yaw: clamp(yaw, 60), pitch: clamp(aim.y - bodyPitch, 35) };
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
    draw(by, bp, head.yaw, head.pitch, clock);
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

  /** Reduced motion: no easing, just redraw once per frame with the head on target. */
  let snapRaf = 0;
  function snap() {
    snapRaf = 0;
    const [by, bp] = body();
    head = headTarget(by, bp);
    draw(by, bp, head.yaw, head.pitch, clock);
  }

  $effect(() => {
    if (!follow) return;
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
      kick();
    };
    const out = (e: PointerEvent) => {
      if (e.relatedTarget) return;
      aim = null;
      kick();
    };
    function kick() {
      if (!motion) {
        if (!snapRaf) snapRaf = requestAnimationFrame(snap);
      } else if (!trackRaf && animate) {
        lastStep = performance.now();
        trackRaf = requestAnimationFrame(track);
      }
    }
    window.addEventListener("pointermove", move, { passive: true });
    document.addEventListener("pointerout", out, { passive: true });
    return () => {
      window.removeEventListener("pointermove", move);
      document.removeEventListener("pointerout", out);
      cancelAnimationFrame(trackRaf);
      cancelAnimationFrame(snapRaf);
      trackRaf = 0;
      snapRaf = 0;
      aim = null;
      head = { yaw: 0, pitch: 0 };
    };
  });

  $effect(() => {
    // Track everything `draw` reads so a static model redraws on change.
    void [geometry, skin, cape, scale, canvas, pitch];
    if (!animate) {
      // Reads `manual` and `yaw`, so dragging redraws a static model too.
      draw(...body(), head.yaw, head.pitch, clock);
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
