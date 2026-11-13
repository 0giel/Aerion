<script lang="ts">
  // The floating island the player stands on (home screen). Small cubes drawn once on a 2D canvas
  // with the same orthographic projection as the player model; it is repainted only when the
  // theme or the size changes, so it costs nothing per frame. All colours come from the theme.
  import { themes } from "../theme/engine.svelte";

  let {
    scale = 7,
    pitch = -20,
    yaw = -28,
  }: {
    /** CSS pixels per model unit (the same unit as the player model: one skin pixel). */
    scale?: number;
    pitch?: number;
    yaw?: number;
  } = $props();

  type Vec3 = [number, number, number];
  type Kind = "grass" | "dirt" | "stone" | "deep" | "trunk" | "leaf" | "gem";
  interface Voxel {
    at: Vec3;
    kind: Kind;
  }

  /** Edge of one cube in model units. */
  const V = 5;
  /** Canvas size in model units; the island's top centre (the player's feet) sits at `FEET`. */
  const WIDTH = 80;
  const HEIGHT = 86;
  const FEET: [number, number] = [40, 42];
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

  /** The island: grid cells (x right, y down, z towards the viewer); y = 0 is the top layer. */
  const voxels: Voxel[] = (() => {
    const list: Voxel[] = [];
    const R = 4.25;
    for (let x = -5; x <= 5; x++)
      for (let z = -5; z <= 5; z++) {
        const r = Math.hypot(x, z * 1.08) + (hash(x, z) - 0.5) * 0.9;
        if (r > R) continue;
        // A low hill at the back left, where the tree grows.
        const hill = Math.hypot(x + 3, z + 1) < 1.5 ? 1 : 0;
        const depth = Math.max(1, Math.round((R - r) * 1.15 + hash(x, z, 7) * 1.3 + (r < 1.2 ? 1 : 0)));
        for (let y = -hill; y < depth; y++) {
          const d = y + hill;
          const kind: Kind =
            d === 0 ? "grass" : d <= 1 + (hash(x, z, 3) > 0.5 ? 1 : 0) ? "dirt" : y > 4 ? "deep" : "stone";
          list.push({ at: [x, y, z], kind });
        }
      }
    // The tree: a short trunk and a blocky crown.
    for (let y = -2; y >= -4; y--) list.push({ at: [-3, y, -1], kind: "trunk" });
    for (let x = -5; x <= -1; x++)
      for (let z = -3; z <= 1; z++)
        for (let y = -5; y >= -7; y--) {
          const far = Math.abs(x + 3) + Math.abs(z + 1) + (y === -7 ? 1 : 0);
          if (far > 1.7 + hash(x, z, y) * 0.9) continue;
          list.push({ at: [x, y, z], kind: "leaf" });
        }
    // A glowing crystal at the front, and two rocks drifting beside the island.
    list.push({ at: [3, -1, 1], kind: "gem" }, { at: [3, -2, 1], kind: "gem" });
    list.push({ at: [-6, 5, 0], kind: "stone" }, { at: [-6, 4, 0], kind: "grass" });
    list.push({ at: [6, 5, -1], kind: "stone" }, { at: [6, 4, -1], kind: "dirt" });
    return list;
  })();

  let canvas: HTMLCanvasElement | undefined = $state();

  type Rgb = [number, number, number];
  /** Any CSS colour → RGB (the canvas normalises it). */
  function parse(ctx: CanvasRenderingContext2D, css: string, fallback: Rgb): Rgb {
    ctx.fillStyle = "#000";
    ctx.fillStyle = css;
    const v = String(ctx.fillStyle);
    if (v.startsWith("#")) return [1, 3, 5].map((i) => parseInt(v.slice(i, i + 2), 16)) as Rgb;
    const m = v.match(/[\d.]+/g);
    return m && m.length >= 3 ? [Number(m[0]), Number(m[1]), Number(m[2])] : fallback;
  }
  const mix = (a: Rgb, b: Rgb, k: number): Rgb => [0, 1, 2].map((i) => a[i] + (b[i] - a[i]) * k) as Rgb;
  const css = (c: Rgb, k: number) =>
    `rgb(${c.map((v) => Math.max(0, Math.min(255, Math.round(v * k)))).join(",")})`;

  const rad = (deg: number) => (deg * Math.PI) / 180;

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
    const palette: Record<Kind, Rgb> = {
      grass: mix([104, 186, 84], accent, 0.3),
      dirt: mix([134, 92, 62], bg, 0.18),
      stone: mix([150, 153, 168], surface, 0.4),
      deep: mix([104, 106, 122], surface, 0.5),
      trunk: [104, 74, 52],
      leaf: mix(accent, [255, 255, 255], 0.08),
      gem: mix(accent, [255, 255, 255], 0.35),
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

    const filled = new Set(voxels.map((v) => v.at.join(",")));
    const sides: { n: Vec3; corners: Vec3[] }[] = [
      {
        n: [0, -1, 0],
        corners: [
          [0, 0, 0],
          [1, 0, 0],
          [1, 0, 1],
          [0, 0, 1],
        ],
      },
      {
        n: [0, 1, 0],
        corners: [
          [0, 1, 0],
          [1, 1, 0],
          [1, 1, 1],
          [0, 1, 1],
        ],
      },
      {
        n: [0, 0, 1],
        corners: [
          [0, 0, 1],
          [1, 0, 1],
          [1, 1, 1],
          [0, 1, 1],
        ],
      },
      {
        n: [0, 0, -1],
        corners: [
          [0, 0, 0],
          [1, 0, 0],
          [1, 1, 0],
          [0, 1, 0],
        ],
      },
      {
        n: [1, 0, 0],
        corners: [
          [1, 0, 0],
          [1, 0, 1],
          [1, 1, 1],
          [1, 1, 0],
        ],
      },
      {
        n: [-1, 0, 0],
        corners: [
          [0, 0, 0],
          [0, 0, 1],
          [0, 1, 1],
          [0, 1, 0],
        ],
      },
    ];
    const seen = sides
      .map((side) => ({ ...side, light: rot(side.n) }))
      .filter((side) => side.light[2] > 1e-6)
      .map((side) => ({
        ...side,
        shade: 0.62 + 0.38 * Math.max(0, side.n[0] * LIGHT[0] + side.n[1] * LIGHT[1] + side.n[2] * LIGHT[2]),
      }));

    const faces: { pts: Vec3[]; depth: number; fill: string }[] = [];
    for (const { at, kind } of voxels) {
      // The top layer is centred on the feet: cell (0, 0, 0) spans −V/2 … V/2 around them.
      const base: Vec3 = [(at[0] - 0.5) * V, at[1] * V, (at[2] - 0.5) * V];
      const grain = 0.94 + hash(at[0], at[2], at[1]) * 0.12;
      for (const side of seen) {
        if (filled.has([at[0] + side.n[0], at[1] + side.n[1], at[2] + side.n[2]].join(","))) continue;
        const pts = side.corners.map((k) =>
          rot([base[0] + k[0] * V, base[1] + k[1] * V, base[2] + k[2] * V]),
        );
        // Grass only caps the top; its sides show the earth below a thin green edge.
        const tone =
          kind === "grass" && side.n[1] === 0 ? mix(palette.grass, palette.dirt, 0.45) : palette[kind];
        faces.push({
          pts,
          depth: pts.reduce((n, p) => n + p[2], 0) / 4,
          fill: css(tone, side.shade * grain * (kind === "gem" ? 1.15 : 1)),
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
      ctx.strokeStyle = f.fill;
      ctx.fill();
      ctx.stroke();
    }

    // The crystal's glow.
    const gem = rot([3 * V, -V, V]);
    const glow = ctx.createRadialGradient(
      ox + gem[0] * s,
      oy + gem[1] * s,
      0,
      ox + gem[0] * s,
      oy + gem[1] * s,
      9 * s,
    );
    glow.addColorStop(0, `rgba(${accent.map(Math.round).join(",")},0.3)`);
    glow.addColorStop(1, `rgba(${accent.map(Math.round).join(",")},0)`);
    ctx.globalCompositeOperation = "lighter";
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, c.width, c.height);
    ctx.globalCompositeOperation = "source-over";
  }

  $effect(() => {
    // Repaint after the theme's variables have reached the page.
    void [themes.active, canvas, scale, pitch, yaw];
    const id = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(id);
  });
</script>

<canvas
  bind:this={canvas}
  aria-hidden="true"
  style:width="{WIDTH * scale}px"
  style:height="{HEIGHT * scale}px"
  style:--feet-x="{FEET[0] * scale}px"
  style:--feet-y="{FEET[1] * scale}px"
></canvas>

<style>
  canvas {
    display: block;
  }
</style>
