<script lang="ts" module>
  /** The drawing's size in model units and where the player's feet stand on it. */
  export const ISLAND = { width: 80, height: 92, feet: [40, 44] as const };
</script>

<script lang="ts">
  // The floating island the player stands on (home screen). Small cubes drawn once on a 2D canvas
  // with the same orthographic projection as the player model; it is repainted only when the
  // theme or the size changes, so it costs nothing per frame. All colours come from the theme.
  import { themes } from "../theme/engine.svelte";

  let {
    scale = 7,
    pitch = -20,
    stage = 3,
    night = false,
  }: {
    /** CSS pixels per model unit (the same unit as the player model: one skin pixel). */
    scale?: number;
    pitch?: number;
    /** How much of the island is built: 0 bare rock, 1 grass and water, 2 plants, 3 everything. */
    stage?: number;
    /** Dimmer and bluer, with the lantern reaching further. */
    night?: boolean;
  } = $props();

  type Vec3 = [number, number, number];
  type Box = [number, number, number, number, number, number];
  type Kind =
    | "grass"
    | "dirt"
    | "stone"
    | "deep"
    | "trunk"
    | "leaf"
    | "bush"
    | "water"
    | "fall"
    | "post"
    | "iron"
    | "gem";
  interface Voxel {
    at: Vec3;
    kind: Kind;
    /** A part of the cell (x0, y0, z0, x1, y1, z1 in shares of a cube) for things smaller than
     *  a cube; they hide nothing behind them. */
    box?: Box;
    /** Part of a small island beside the big one (built last). */
    far?: boolean;
  }
  const STAGE: Record<Kind, number> = {
    deep: 0,
    stone: 0,
    dirt: 0,
    grass: 1,
    water: 1,
    fall: 1,
    trunk: 2,
    leaf: 2,
    bush: 2,
    post: 3,
    iron: 3,
    gem: 3,
  };

  /** Edge of one cube in model units. */
  const V = 4;
  /** The view is turned so the front and the left side show; the left catches the light. */
  const YAW = 28;
  /** Radius of the island's top in cubes. */
  const R = 6.4;
  /** The cell of the lantern's light (it stands on the grass two cells below). */
  const LAMP: Vec3 = [-5, -3, 1];

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

  /** The island: grid cells (x right, y down, z towards the viewer); y = 0 is the top layer.
   *  Laid out for the view: the tree stands back on the left, a terrace rises behind the player,
   *  the pond spills over the front on the right, the lantern lights the near left. Nothing tall
   *  stands in front of the player, who is drawn over the island. */
  const voxels: Voxel[] = (() => {
    const list: Voxel[] = [];
    const radius = (x: number, z: number) => Math.hypot(x, z) + (noise(x / 2.4, z / 2.4, 5) - 0.5) * 1.1;
    const inside = (x: number, z: number) => radius(x, z) <= R;
    /** How far back a cell lies in the view (negative is behind the player). */
    const back = (x: number, z: number) => -0.469 * x + 0.883 * z;
    const pond = (x: number, z: number) =>
      inside(x, z) && (Math.hypot(x - 3.5, z - 3) < 1.6 || (x === 4 && z > 3));
    /** The terrace: one step up behind the player, two at the very back. */
    const rise = (x: number, z: number) => {
      if (pond(x, z) || radius(x, z) > R - 0.6) return 0;
      const b = back(x, z) + (noise(x / 2, z / 2, 11) - 0.5) * 0.9;
      return b < -4.5 ? 2 : b < -2.7 ? 1 : 0;
    };
    /** Stalactites: a few places where the rock hangs lower. */
    const spikes: [number, number, number][] = [
      [0, 0, 3],
      [-2, 1, 2],
      [2, -2, 2],
    ];

    for (let x = -8; x <= 8; x++)
      for (let z = -8; z <= 8; z++) {
        if (!inside(x, z)) continue;
        const r = radius(x, z);
        const up = rise(x, z);
        const spike = spikes.reduce(
          (n, [sx, sz, len]) => Math.max(n, len - Math.hypot(x - sx, z - sz) * 1.6),
          0,
        );
        const depth = Math.max(
          1,
          Math.round(1 + (R - r) * 0.95 + noise(x / 1.7 + 9, z / 1.7, 2) * 1.4 + spike),
        );
        const soil = r < 3 ? 2 : 1;
        for (let y = -up; y < depth; y++) {
          const d = y + up;
          let kind: Kind;
          if (d === 0) kind = pond(x, z) ? "water" : "grass";
          else if (d <= soil) kind = "dirt";
          else kind = y > 4 ? "deep" : "stone";
          // Water lies a little below the bank.
          list.push(
            kind === "water" ? { at: [x, y, z], kind, box: [0, 0.18, 0, 1, 1, 1] } : { at: [x, y, z], kind },
          );
        }
      }

    // The waterfall: a thin sheet from the end of the channel down past the island.
    let edge = 3;
    while (inside(4, edge)) edge++;
    for (let y = 0; y < 10; y++)
      list.push({ at: [4, y, edge], kind: "fall", box: [0.06, 0, 0, 0.94, 1, 0.2] });

    // The tree: a trunk and a rounded crown.
    const tree: Vec3 = [-5, -rise(-5, -2), -2];
    for (let n = 1; n <= 3; n++)
      list.push({ at: [tree[0], tree[1] - n, tree[2]], kind: "trunk", box: [0.12, 0, 0.12, 0.88, 1, 0.88] });
    for (let x = -3; x <= 3; x++)
      for (let z = -3; z <= 3; z++)
        for (let y = -3; y <= 2; y++) {
          const far = Math.hypot(x, y * 1.15 + 0.2, z) + hash(x + 50, z, y) * 0.45;
          if (far > 3.05) continue;
          list.push({ at: [tree[0] + x, tree[1] - 6 + y, tree[2] + z], kind: "leaf" });
        }
    // A bush on the terrace.
    const bush: Vec3 = [5, -rise(5, -2), -2];
    for (const [x, y, z] of [
      [0, 1, 0],
      [1, 1, 0],
      [-1, 1, 0],
      [0, 1, 1],
      [0, 1, -1],
      [0, 2, 0],
    ] as Vec3[])
      if (inside(bush[0] + x, bush[2] + z))
        list.push({ at: [bush[0] + x, bush[1] - y, bush[2] + z], kind: "bush" });
    // The lantern: a wooden post carrying a lit glass block between a dark base and a dark roof
    // with a knob on top. Heights are in cubes above the grass; a part may span two cells.
    const part = (kind: Kind, half: number, from: number, to: number) => {
      for (let cell = Math.floor(from); cell < to; cell++) {
        const [low, high] = [Math.max(from, cell) - cell, Math.min(to, cell + 1) - cell];
        list.push({
          at: [LAMP[0], -1 - cell, LAMP[2]],
          kind,
          box: [0.5 - half, 1 - high, 0.5 - half, 0.5 + half, 1 - low, 0.5 + half],
        });
      }
    };
    part("post", 0.15, 0, 1.55);
    part("iron", 0.36, 1.55, 1.72);
    part("gem", 0.26, 1.72, 2.4);
    part("iron", 0.4, 2.4, 2.58);
    part("iron", 0.12, 2.58, 2.74);

    // Small islands drifting around the big one.
    const islet = (cx: number, cy: number, cz: number, size: number) => {
      // An upside-down cone: each layer down is narrower than the one above.
      for (let y = 0; y < 6; y++)
        for (let x = -3; x <= 3; x++)
          for (let z = -3; z <= 3; z++) {
            if (Math.hypot(x, z) + y * 0.5 + hash(x + cx, z + cz, 60) * 0.15 > size) continue;
            list.push({
              at: [cx + x, cy + y, cz + z],
              kind: y === 0 ? "grass" : y === 1 ? "dirt" : "stone",
              far: true,
            });
          }
    };
    islet(-8, 7, -1, 1.5);
    islet(7, 7, 2, 2.1);
    islet(9, -6, -3, 1.5);
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
  /** The faces of a cube; a side's first two corners are its upper edge. `light` is how bright
   *  the face is: the top catches the sky, the front more than the sides. */
  const SIDES: { n: Vec3; corners: Vec3[]; light: number }[] = [
    { n: [0, -1, 0], corners: quad([0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]), light: 1 },
    { n: [0, 1, 0], corners: quad([0, 1, 0], [1, 1, 0], [1, 1, 1], [0, 1, 1]), light: 0.5 },
    { n: [0, 0, 1], corners: quad([0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]), light: 0.8 },
    { n: [0, 0, -1], corners: quad([0, 0, 0], [1, 0, 0], [1, 1, 0], [0, 1, 0]), light: 0.6 },
    { n: [1, 0, 0], corners: quad([1, 0, 0], [1, 0, 1], [1, 1, 1], [1, 1, 0]), light: 0.66 },
    { n: [-1, 0, 0], corners: quad([0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]), light: 0.66 },
  ];

  function draw() {
    const c = canvas;
    const ctx = c?.getContext("2d");
    if (!c || !ctx) return;
    const dpr = window.devicePixelRatio || 1;
    c.width = Math.round(ISLAND.width * scale * dpr);
    c.height = Math.round(ISLAND.height * scale * dpr);

    const style = getComputedStyle(c);
    const token = (name: string, fallback: Rgb) =>
      parse(ctx, style.getPropertyValue(name).trim() || "#000", fallback);
    const accent = token("--c-accent", [251, 191, 36]);
    const bg = token("--c-bg", [22, 23, 27]);
    const surface = token("--c-surface-3", [49, 51, 59]);
    const grass = mix([104, 190, 84], accent, 0.22);
    const glowing = mix(accent, [255, 255, 255], 0.45);
    const palette: Record<Kind, Rgb> = {
      grass,
      dirt: mix([140, 98, 66], bg, 0.12),
      stone: mix([156, 160, 176], surface, 0.36),
      deep: mix([118, 121, 138], surface, 0.44),
      trunk: [112, 80, 56],
      leaf: mix(accent, [255, 255, 255], 0.06),
      bush: mix(grass, [40, 116, 64], 0.5),
      water: mix([84, 168, 236], accent, 0.14),
      fall: mix([160, 214, 248], accent, 0.1),
      post: [124, 88, 60],
      iron: [52, 50, 58],
      gem: glowing,
    };
    const dusk: Rgb = [70, 92, 156];

    const [sy, cy] = [Math.sin(rad(YAW)), Math.cos(rad(YAW))];
    const [sp, cp] = [Math.sin(rad(pitch)), Math.cos(rad(pitch))];
    const rot = ([x, y, z]: Vec3): Vec3 => {
      const x1 = x * cy + z * sy;
      const z1 = -x * sy + z * cy;
      return [x1, y * cp - z1 * sp, y * sp + z1 * cp];
    };
    const s = scale * dpr;
    const ox = ISLAND.feet[0] * s;
    const oy = ISLAND.feet[1] * s;

    const built = voxels.filter((v) => (v.far ? 3 : STAGE[v.kind]) <= stage);
    // Full cubes hide the faces between them; small things (the lantern, the water) hide nothing.
    // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
    const filled = new Set(built.filter((v) => !v.box).map((v) => v.at.join(",")));
    // eslint-disable-next-line svelte/prefer-svelte-reactivity -- local lookup, not UI state
    const falling = new Set(built.filter((v) => v.kind === "fall").map((v) => v.at.join(",")));
    const has = (x: number, y: number, z: number) => filled.has(`${x},${y},${z}`);
    const seen = SIDES.filter((side) => rot(side.n)[2] > 1e-6);
    /** Something stands above this cell (the tree's crown), so the ground lies in its shadow. */
    const shaded = (x: number, y: number, z: number) => {
      for (let n = 2; n <= 10; n++) if (has(x, y - n, z)) return true;
      return false;
    };
    const lit = stage >= 3;
    const reach = night ? 4.6 : 3;

    const faces: { pts: Vec3[]; depth: number; fill: string; edge?: string }[] = [];
    for (const { at, kind, box } of built) {
      const [x0, y0, z0, x1, y1, z1] = box ?? [0, 0, 0, 1, 1, 1];
      // The top layer is centred on the feet: cell (0, 0, 0) spans −V/2 … V/2 around them.
      const base: Vec3 = [(at[0] - 0.5 + x0) * V, (at[1] + y0) * V, (at[2] - 0.5 + z0) * V];
      const size: Vec3 = [(x1 - x0) * V, (y1 - y0) * V, (z1 - z0) * V];
      const grain =
        kind === "leaf" || kind === "bush"
          ? 0.9 + hash(at[0], at[2], at[1]) * 0.16
          : 0.965 + hash(at[0], at[2], at[1]) * 0.07;
      // Lighter and darker patches of grass.
      const patch = kind === "grass" ? 0.95 + noise(at[0] / 2, at[2] / 2, 70) * 0.1 : 1;
      // How strongly the lantern lights this cell.
      const glow = lit
        ? Math.max(0, 1 - Math.hypot(at[0] - LAMP[0], (at[1] - LAMP[1]) * 0.6, at[2] - LAMP[2]) / reach)
        : 0;
      for (const side of seen) {
        const [nx, ny, nz] = side.n;
        if (
          box
            ? kind === "fall" && falling.has(`${at[0] + nx},${at[1] + ny},${at[2] + nz}`)
            : has(at[0] + nx, at[1] + ny, at[2] + nz)
        )
          continue;
        const top = ny === -1;
        let light = side.light * grain * patch;
        // Corners where the ground meets something taller are darker, and so is ground under
        // the tree; lower rock fades into the dark.
        if (top && !box) {
          const walls =
            Number(has(at[0] + 1, at[1] - 1, at[2])) +
            Number(has(at[0] - 1, at[1] - 1, at[2])) +
            Number(has(at[0], at[1] - 1, at[2] + 1)) +
            Number(has(at[0], at[1] - 1, at[2] - 1));
          light *= 1 - 0.07 * walls;
          if (shaded(at[0], at[1], at[2])) light *= 0.86;
        }
        if (!top && at[1] > 0) light *= Math.max(0.6, 1 - at[1] * 0.045);
        if (kind === "gem") light = 1.12;

        /** One quad of this face, between two heights of the cube (0 top, 1 bottom). */
        const add = (from: number, to: number, base0: Rgb, edge?: boolean) => {
          let tone = base0;
          if (night && kind !== "gem") tone = mix(tone, dusk, 0.1);
          // The lantern lights the ground around it, not its own post and frame.
          if (glow > 0 && kind !== "gem" && kind !== "post" && kind !== "iron")
            tone = mix(tone, glowing, glow * 0.42);
          const k = (night && kind !== "gem" ? 0.92 : 1) * light * (1 + glow * 0.3);
          const pts = side.corners.map((q) =>
            rot([
              base[0] + q[0] * size[0],
              base[1] + (top || ny === 1 ? q[1] : from + q[1] * (to - from)) * size[1],
              base[2] + q[2] * size[2],
            ]),
          );
          // Falling water thins out and fades into the sky.
          const alpha = kind === "fall" ? Math.max(0.1, 0.78 - at[1] * 0.075) : kind === "water" ? 0.94 : 1;
          faces.push({
            pts,
            depth: pts.reduce((n, p) => n + p[2], 0) / 4 + (box ? 0.01 : 0),
            fill: paint(kind === "fall" ? mix(tone, bg, Math.min(0.6, at[1] * 0.06)) : tone, k, alpha),
            edge: edge ? paint(mix(tone, [255, 255, 255], 0.22), k * 1.06) : undefined,
          });
        };
        if (kind === "grass" && ny === 0) {
          // Grass caps the top: its sides are a green lip over the earth, with a bright edge.
          // The lip hangs lower in some places than in others.
          const lip = 0.28 + hash(at[0], at[2], 21) * 0.4;
          add(0, lip, palette.grass, true);
          add(lip, 1, palette.dirt);
        } else if (kind === "fall" && nz === 1) {
          // Streaks: every other stretch of the sheet is a touch lighter.
          add(0, 1, at[1] % 2 ? palette.fall : mix(palette.fall, [255, 255, 255], 0.2));
        } else {
          add(0, 1, palette[kind]);
        }
      }
    }
    faces.sort((a, b) => a.depth - b.depth);

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, c.width, c.height);
    ctx.lineJoin = "round";
    ctx.lineCap = "round";
    const at2 = (p: Vec3): [number, number] => [ox + p[0] * s, oy + p[1] * s];
    for (const f of faces) {
      ctx.beginPath();
      f.pts.forEach((p, i) => (i ? ctx.lineTo(...at2(p)) : ctx.moveTo(...at2(p))));
      ctx.closePath();
      ctx.fillStyle = f.fill;
      ctx.fill();
      // A hairline in the same colour hides the seams between opaque cubes.
      if (f.fill.endsWith(",1)")) {
        ctx.lineWidth = Math.max(1, dpr * 0.75);
        ctx.strokeStyle = f.fill;
        ctx.stroke();
      }
      if (f.edge) {
        ctx.beginPath();
        ctx.moveTo(...at2(f.pts[0]));
        ctx.lineTo(...at2(f.pts[1]));
        ctx.lineWidth = Math.max(1, s * 0.14);
        ctx.strokeStyle = f.edge;
        ctx.stroke();
      }
    }

    if (!lit) return;
    // The lantern's halo.
    const lamp = rot([LAMP[0] * V, (LAMP[1] + 0.95) * V, LAMP[2] * V]);
    const [gx, gy] = at2(lamp);
    const halo = ctx.createRadialGradient(gx, gy, 0, gx, gy, (night ? 13 : 8) * s);
    halo.addColorStop(0, `rgba(${accent.map(Math.round).join(",")},${night ? 0.3 : 0.24})`);
    halo.addColorStop(1, `rgba(${accent.map(Math.round).join(",")},0)`);
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = halo;
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.globalCompositeOperation = "source-over";
  }

  $effect(() => {
    // Repaint after the theme's variables have reached the page.
    void [themes.active, canvas, scale, pitch, stage, night];
    const id = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(id);
  });
</script>

<canvas
  bind:this={canvas}
  aria-hidden="true"
  style:width="{ISLAND.width * scale}px"
  style:height="{ISLAND.height * scale}px"
></canvas>

<style>
  canvas {
    display: block;
  }
</style>
