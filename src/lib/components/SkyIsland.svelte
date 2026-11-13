<script lang="ts">
  // The floating island the player stands on (home screen). Small cubes drawn once on a 2D canvas
  // with the same orthographic projection as the player model; it is repainted only when the
  // theme or the size changes, so it costs nothing per frame. All colours come from the theme.
  import { themes } from "../theme/engine.svelte";

  let {
    scale = 7,
    pitch = -20,
    yaw = -28,
    stage = 3,
  }: {
    /** How much of the island is built: 0 bare rock, 1 grass and water, 2 plants, 3 everything. */
    stage?: number;
    /** CSS pixels per model unit (the same unit as the player model: one skin pixel). */
    scale?: number;
    pitch?: number;
    yaw?: number;
  } = $props();

  type Vec3 = [number, number, number];
  type Kind =
    | "grass"
    | "moss"
    | "dirt"
    | "stone"
    | "deep"
    | "ore"
    | "trunk"
    | "leaf"
    | "bush"
    | "water"
    | "fall"
    | "post"
    | "gem"
    | "bloomA"
    | "bloomB";
  interface Voxel {
    at: Vec3;
    kind: Kind;
    /** Width and height as a share of a full cube (small things stand on the cube's floor). */
    w?: number;
    h?: number;
    /** Part of a small island beside the big one (built last). */
    far?: boolean;
  }
  const STAGE: Record<Kind, number> = {
    deep: 0,
    stone: 0,
    ore: 0,
    dirt: 0,
    grass: 1,
    moss: 1,
    water: 1,
    fall: 1,
    trunk: 2,
    leaf: 2,
    bush: 2,
    bloomA: 2,
    bloomB: 2,
    post: 3,
    gem: 3,
  };

  /** Edge of one cube in model units. */
  const V = 4;
  /** Canvas size in model units; the island's top centre (the player's feet) sits at `FEET`. */
  const WIDTH = 92;
  const HEIGHT = 98;
  const FEET: [number, number] = [46, 50];
  /** Where the lantern's light sits (grid cell), for its glow. */
  const LAMP: Vec3 = [4, -2, 2];
  const LIGHT: Vec3 = (() => {
    const l: Vec3 = [-0.42, -0.55, 0.72];
    const n = Math.hypot(...l);
    return [l[0] / n, l[1] / n, l[2] / n];
  })();

  /** Repeatable pseudo-random number in 0..1 for a grid cell. */
  function hash(a: number, b: number, c = 0): number {
    let h = (a * 374761393 + b * 668265263 + c * 2147483647) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    return ((h ^ (h >>> 16)) >>> 0) / 4294967295;
  }
  /** Smooth noise in 0..1: random values on a grid, blended in between. */
  function noise(x: number, z: number, seed = 0): number {
    const [x0, z0] = [Math.floor(x), Math.floor(z)];
    const [fx, fz] = [x - x0, z - z0];
    const ease = (t: number) => t * t * (3 - 2 * t);
    const at = (i: number, j: number) => hash(x0 + i, z0 + j, seed);
    const top = at(0, 0) + (at(1, 0) - at(0, 0)) * ease(fx);
    const low = at(0, 1) + (at(1, 1) - at(0, 1)) * ease(fx);
    return top + (low - top) * ease(fz);
  }

  /** The island: grid cells (x right, y down, z towards the viewer); y = 0 is the top layer. */
  const voxels: Voxel[] = (() => {
    const list: Voxel[] = [];
    const R = 5.6;
    const radius = (x: number, z: number) => Math.hypot(x, z * 1.1) + (noise(x / 2.2, z / 2.2, 5) - 0.5) * 2;
    const inside = (x: number, z: number) => radius(x, z) <= R;
    // The pond at the front left, with a channel to the edge where the water falls off.
    const pond = (x: number, z: number) =>
      inside(x, z) && (Math.hypot(x + 3, z - 2.6) < 1.5 || (x === -3 && z > 2));
    /** How many cubes the ground rises here. Flat around the player and in front of them. */
    const rise = (x: number, z: number) => {
      const r = radius(x, z);
      if (r < 2.2 || r > R - 1 || pond(x, z) || (z > -1 && Math.abs(x) < 3.5)) return 0;
      return Math.max(0, Math.min(2, Math.round(noise(x / 2.6, z / 2.6, 11) * 3.4 - 0.9)));
    };

    for (let x = -8; x <= 8; x++)
      for (let z = -8; z <= 8; z++) {
        if (!inside(x, z)) continue;
        const r = radius(x, z);
        const up = rise(x, z);
        const rim = !inside(x + 1, z) || !inside(x - 1, z) || !inside(x, z + 1) || !inside(x, z - 1);
        // The underside tapers to a few uneven spikes.
        const depth = Math.max(
          1,
          Math.round((R - r) * 0.85 + noise(x / 1.6 + 9, z / 1.6 + 3, 2) * 3.6 + (r < 1.6 ? 2 : 0)),
        );
        const mossy = rim && hash(x, z, 21) < 0.45 ? 1 + Math.round(hash(x, z, 22) * 2) : 0;
        for (let y = -up; y < depth; y++) {
          const d = y + up;
          let kind: Kind;
          if (d === 0) kind = pond(x, z) ? "water" : "grass";
          else if (d <= mossy) kind = "moss";
          else if (d <= 1 + (hash(x, z, 3) > 0.5 ? 1 : 0)) kind = "dirt";
          else if (hash(x, z, y + 40) < 0.05) kind = "ore";
          else kind = y > 4 ? "deep" : "stone";
          list.push({ at: [x, y, z], kind });
        }
        // Flowers scattered on the grass.
        const pick = hash(x, z, 31);
        if (!pond(x, z) && r > 2.4 && pick < 0.1)
          list.push({ at: [x, -up - 1, z], kind: pick < 0.05 ? "bloomA" : "bloomB", w: 0.42, h: 0.42 });
      }

    // The waterfall, from the end of the channel down past the island.
    let edge = 3;
    while (inside(-3, edge)) edge++;
    for (let y = 0; y < 9; y++) list.push({ at: [-3, y, edge], kind: "fall", w: 0.8 });

    // The tree: a trunk and a rounded crown.
    const tree: Vec3 = [-4, -rise(-4, -1), -1];
    for (let n = 1; n <= 4; n++) list.push({ at: [tree[0], tree[1] - n, tree[2]], kind: "trunk", w: 0.8 });
    for (let x = -3; x <= 3; x++)
      for (let z = -3; z <= 3; z++)
        for (let y = -3; y <= 2; y++) {
          const far = Math.hypot(x, y * 1.25 + 0.4, z) + hash(x + 50, z, y) * 0.7;
          if (far > 2.9) continue;
          list.push({ at: [tree[0] + x, tree[1] - 7 + y, tree[2] + z], kind: "leaf" });
        }
    // A bush at the back right.
    const bush: Vec3 = [3, -rise(3, -4), -4];
    const twigs: Vec3[] = [
      [0, 1, 0],
      [1, 1, 0],
      [0, 1, 1],
      [0, 2, 0],
      [-1, 1, 0],
    ];
    for (const [x, y, z] of twigs)
      if (inside(bush[0] + x, bush[2] + z))
        list.push({ at: [bush[0] + x, bush[1] - y, bush[2] + z], kind: "bush" });
    // The lantern: a post with a glowing block on top.
    list.push({ at: [LAMP[0], -1, LAMP[2]], kind: "post", w: 0.3 });
    list.push({ at: [LAMP[0], LAMP[1], LAMP[2]], kind: "gem", w: 0.7, h: 0.7 });

    // Small islands drifting around the big one.
    const islet = (cx: number, cy: number, cz: number, size: number, extra?: Kind) => {
      for (let x = -2; x <= 2; x++)
        for (let z = -2; z <= 2; z++) {
          const r = Math.hypot(x, z) + hash(x + cx, z + cz, 60) * 0.5;
          if (r > size) continue;
          const depth = Math.max(1, Math.round((size - r) * 1.8));
          for (let y = 0; y < depth; y++)
            list.push({
              at: [cx + x, cy + y, cz + z],
              kind: y === 0 ? "grass" : y === 1 ? "dirt" : "stone",
              far: true,
            });
        }
      if (extra) list.push({ at: [cx, cy - 1, cz], kind: extra, far: true });
    };
    islet(-7, 5, 2, 1.2);
    islet(7, 6, 1, 1.7, "bush");
    islet(4, -8, -7, 1.2);
    return list;
  })();

  let canvas: HTMLCanvasElement | undefined = $state();

  type Rgb = [number, number, number];
  /** Any CSS colour → RGB (the canvas normalises it). */
  function parse(ctx: CanvasRenderingContext2D, value: string, fallback: Rgb): Rgb {
    ctx.fillStyle = "#000";
    ctx.fillStyle = value;
    const v = String(ctx.fillStyle);
    if (v.startsWith("#")) return [1, 3, 5].map((i) => parseInt(v.slice(i, i + 2), 16)) as Rgb;
    const m = v.match(/[\d.]+/g);
    return m && m.length >= 3 ? [Number(m[0]), Number(m[1]), Number(m[2])] : fallback;
  }
  const mix = (a: Rgb, b: Rgb, k: number): Rgb => [0, 1, 2].map((i) => a[i] + (b[i] - a[i]) * k) as Rgb;
  const paint = (c: Rgb, k: number, alpha = 1) =>
    `rgba(${c.map((v) => Math.max(0, Math.min(255, Math.round(v * k)))).join(",")},${alpha})`;

  const rad = (deg: number) => (deg * Math.PI) / 180;

  const quad = (a: Vec3, b: Vec3, c: Vec3, d: Vec3): Vec3[] => [a, b, c, d];
  const SIDES: { n: Vec3; corners: Vec3[] }[] = [
    { n: [0, -1, 0], corners: quad([0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]) },
    { n: [0, 1, 0], corners: quad([0, 1, 0], [1, 1, 0], [1, 1, 1], [0, 1, 1]) },
    { n: [0, 0, 1], corners: quad([0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]) },
    { n: [0, 0, -1], corners: quad([0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0]) },
    { n: [1, 0, 0], corners: quad([1, 0, 0], [1, 0, 1], [1, 1, 1], [1, 1, 0]) },
    { n: [-1, 0, 0], corners: quad([0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]) },
  ];

  function draw() {
    const c = canvas;
    const ctx = c?.getContext("2d");
    if (!c || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = Math.round(WIDTH * scale * dpr);
    c.height = Math.round(HEIGHT * scale * dpr);

    const style = getComputedStyle(c);
    const token = (name: string, fallback: Rgb) =>
      parse(ctx, style.getPropertyValue(name).trim() || "#000", fallback);
    const accent = token("--c-accent", [251, 191, 36]);
    const bg = token("--c-bg", [22, 23, 27]);
    const surface = token("--c-surface-3", [49, 51, 59]);
    const grass = mix([98, 184, 82], accent, 0.28);
    const palette: Record<Kind, Rgb> = {
      grass,
      moss: mix(grass, [40, 96, 52], 0.45),
      dirt: mix([134, 92, 62], bg, 0.18),
      stone: mix([150, 153, 168], surface, 0.4),
      deep: mix([104, 106, 122], surface, 0.5),
      ore: mix(accent, [255, 255, 255], 0.15),
      trunk: [104, 74, 52],
      leaf: mix(accent, [255, 255, 255], 0.08),
      bush: mix(grass, [36, 110, 60], 0.5),
      water: mix([86, 170, 232], accent, 0.18),
      fall: mix([150, 208, 245], accent, 0.12),
      post: [70, 62, 60],
      gem: mix(accent, [255, 255, 255], 0.4),
      bloomA: [246, 244, 238],
      bloomB: mix(accent, [255, 255, 255], 0.3),
    };

    const [sy, cy] = [Math.sin(rad(yaw)), Math.cos(rad(yaw))];
    const [sp, cp] = [Math.sin(rad(pitch)), Math.cos(rad(pitch))];
    const rot = ([x, y, z]: Vec3): Vec3 => {
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      return [x1, y * cp - z1 * sp, y * sp + z1 * cp];
    };
    const s = scale * dpr;
    const ox = FEET[0] * s;
    const oy = FEET[1] * s;

    // Full cubes hide the faces between them; small things (flowers, the lantern) hide nothing.
    // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
    const built = voxels.filter((v) => (v.far ? 3 : STAGE[v.kind]) <= stage);
    // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
    const filled = new Set(built.filter((v) => !v.w && !v.h).map((v) => v.at.join(",")));
    // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
    const falling = new Set(built.filter((v) => v.kind === "fall").map((v) => v.at.join(",")));
    const seen = SIDES.filter((side) => rot(side.n)[2] > 1e-6).map((side) => ({
      ...side,
      shade: 0.6 + 0.4 * Math.max(0, side.n[0] * LIGHT[0] + side.n[1] * LIGHT[1] + side.n[2] * LIGHT[2]),
    }));
    /** Something stands above this cell (the tree's crown), so the ground lies in its shadow. */
    const shaded = (x: number, y: number, z: number) => {
      for (let n = 2; n <= 9; n++) if (filled.has([x, y - n, z].join(","))) return true;
      return false;
    };

    const faces: { pts: Vec3[]; depth: number; fill: string }[] = [];
    for (const { at, kind, w = 1, h = 1 } of built) {
      // The top layer is centred on the feet: cell (0, 0, 0) spans −V/2 … V/2 around them.
      const base: Vec3 = [
        (at[0] - 0.5 + (1 - w) / 2) * V,
        (at[1] + 1 - h) * V,
        (at[2] - 0.5 + (1 - w) / 2) * V,
      ];
      const size: Vec3 = [w * V, h * V, w * V];
      const small = w < 1 || h < 1;
      const grain = 0.93 + hash(at[0], at[2], at[1]) * 0.14;
      // Lighter and darker patches of grass.
      const patch = kind === "grass" ? 0.92 + noise(at[0] / 1.8, at[2] / 1.8, 70) * 0.18 : 1;
      for (const side of seen) {
        const next = [at[0] + side.n[0], at[1] + side.n[1], at[2] + side.n[2]].join(",");
        if (small ? kind === "fall" && falling.has(next) : filled.has(next)) continue;
        const pts = side.corners.map((k) =>
          rot([base[0] + k[0] * size[0], base[1] + k[1] * size[1], base[2] + k[2] * size[2]]),
        );
        const top = side.n[1] === -1;
        // Grass only caps the top; its sides show the earth below a green edge.
        let tone = kind === "grass" && !top ? mix(palette.grass, palette.dirt, 0.4) : palette[kind];
        let light = side.shade * grain * patch;
        if (kind === "gem" || kind === "ore") light = Math.max(light, 0.95) * 1.1;
        // Lower rock is darker, and so is ground under the tree.
        if (!top && at[1] > 0) light *= Math.max(0.72, 1 - at[1] * 0.035);
        if (top && (kind === "grass" || kind === "water") && shaded(at[0], at[1], at[2])) light *= 0.8;
        // Falling water thins out and fades into the sky.
        if (kind === "fall") tone = mix(tone, bg, Math.min(0.75, at[1] * 0.085));
        const alpha = kind === "fall" ? Math.max(0.12, 0.7 - at[1] * 0.07) : kind === "water" ? 0.92 : 1;
        faces.push({
          pts,
          depth: pts.reduce((n, p) => n + p[2], 0) / 4 + (small ? 0.01 : 0),
          fill: paint(tone, light, alpha),
        });
      }
    }
    faces.sort((a, b) => a.depth - b.depth);

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    ctx.lineJoin = "round";
    ctx.lineWidth = Math.max(1, dpr * 0.75);
    for (const f of faces) {
      ctx.beginPath();
      f.pts.forEach((p, i) =>
        i ? ctx.lineTo(ox + p[0] * s, oy + p[1] * s) : ctx.moveTo(ox + p[0] * s, oy + p[1] * s),
      );
      ctx.closePath();
      ctx.fillStyle = f.fill;
      ctx.fill();
      // A hairline in the same colour hides the seams between opaque cubes.
      if (f.fill.endsWith(",1)")) {
        ctx.strokeStyle = f.fill;
        ctx.stroke();
      }
    }

    if (stage < 3) return;
    // The lantern's glow.
    const lamp = rot([LAMP[0] * V, (LAMP[1] + 0.6) * V, LAMP[2] * V]);
    const [gx, gy] = [ox + lamp[0] * s, oy + lamp[1] * s];
    const glow = ctx.createRadialGradient(gx, gy, 0, gx, gy, 11 * s);
    glow.addColorStop(0, `rgba(${accent.map(Math.round).join(",")},0.34)`);
    glow.addColorStop(1, `rgba(${accent.map(Math.round).join(",")},0)`);
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.globalCompositeOperation = "source-over";
  }

  $effect(() => {
    // Repaint after the theme's variables have reached the page.
    void [themes.active, canvas, scale, pitch, yaw, stage];
    const id = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(id);
  });
</script>

<canvas
  bind:this={canvas}
  aria-hidden="true"
  style:width="{WIDTH * scale}px"
  style:height="{HEIGHT * scale}px"
></canvas>

<style>
  canvas {
    display: block;
  }
</style>
