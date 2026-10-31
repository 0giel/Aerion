<script lang="ts">
  import type { Particles } from "../theme/schema";
  import { onAmbientFrame } from "../stores/ambient.svelte";

  let { kind, animate }: { kind: Exclude<Particles, "none">; animate: boolean } = $props();
  let canvas: HTMLCanvasElement | undefined = $state();

  interface P {
    x: number;
    y: number;
    r: number;
    vx: number;
    vy: number;
    phase: number;
    speed: number;
    /** Last painted box, cleared before the next frame. */
    box: [number, number, number, number] | null;
  }

  // Scene state shared by the set-up and animation effects, so pausing keeps the positions.
  let ctx: CanvasRenderingContext2D | null = null;
  let w = 0;
  let h = 0;
  let parts: P[] = [];
  let color = "#fff";
  let t = 0;

  const spawn = (anywhere: boolean): P => ({
    x: Math.random() * w,
    y: anywhere ? Math.random() * h : kind === "snow" ? -10 : h + 10,
    r:
      kind === "sparkles"
        ? 2.5 + Math.random() * 4.5
        : kind === "snow"
          ? 1 + Math.random() * 2.2
          : 0.6 + Math.random() * 1.6,
    vx: (Math.random() - 0.5) * (kind === "snow" ? 0.25 : 0.12),
    vy: kind === "snow" ? 0.25 + Math.random() * 0.45 : -(0.05 + Math.random() * 0.18),
    phase: Math.random() * Math.PI * 2,
    speed: 0.4 + Math.random() * 1.2,
    box: null,
  });

  const star = (c: CanvasRenderingContext2D, p: P) => {
    const r = p.r;
    c.beginPath();
    c.moveTo(p.x, p.y - r * 2);
    c.quadraticCurveTo(p.x, p.y, p.x + r * 2, p.y);
    c.quadraticCurveTo(p.x, p.y, p.x, p.y + r * 2);
    c.quadraticCurveTo(p.x, p.y, p.x - r * 2, p.y);
    c.quadraticCurveTo(p.x, p.y, p.x, p.y - r * 2);
    c.fill();
  };

  // Only the boxes around particles are cleared and redrawn: clearing the whole canvas would
  // make the engine repaint the entire window (and every blurred glass card) on each frame.
  function draw() {
    const c = ctx;
    if (!c) return;
    for (const p of parts) if (p.box) c.clearRect(...p.box);
    c.fillStyle = color;
    for (const p of parts) {
      const tw = 0.5 + 0.5 * Math.sin(t * 0.02 * p.speed + p.phase);
      if (kind === "sparkles") {
        c.globalAlpha = 0.08 + 0.32 * tw;
        star(c, p);
      } else {
        c.globalAlpha = kind === "snow" ? 0.35 + 0.2 * tw : 0.12 + 0.25 * tw;
        c.beginPath();
        c.arc(p.x, p.y, p.r, 0, Math.PI * 2);
        c.fill();
      }
      const e = (kind === "sparkles" ? 2 * p.r : p.r) + 2;
      p.box = [p.x - e, p.y - e, 2 * e, 2 * e];
    }
    c.globalAlpha = 1;
  }

  $effect(() => {
    const c = canvas;
    const context = c?.getContext("2d");
    if (!c || !context) return;
    ctx = context;
    color = getComputedStyle(document.documentElement).getPropertyValue("--c-text").trim() || "#fff";
    const count = kind === "snow" ? 70 : kind === "dust" ? 55 : 34;
    const resize = () => {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      w = c.clientWidth;
      h = c.clientHeight;
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      parts = Array.from({ length: count }, () => spawn(true));
      draw();
    };
    resize();
    const ro = new ResizeObserver(resize);
    ro.observe(c);
    return () => {
      ro.disconnect();
      ctx = null;
    };
  });

  $effect(() => {
    if (!animate || !canvas) return;
    return onAmbientFrame((dt) => {
      const steps = dt / 16.7;
      t += steps;
      for (const [i, p] of parts.entries()) {
        p.x += p.vx * steps + Math.sin((t + p.phase * 50) * 0.01) * 0.08;
        p.y += p.vy * steps;
        if (p.y < -12 || p.y > h + 12 || p.x < -12 || p.x > w + 12) {
          // Keep the old box so the next frame still clears the particle's last position.
          parts[i] = { ...spawn(false), box: p.box };
        }
      }
      draw();
    });
  });
</script>

<canvas bind:this={canvas}></canvas>

<style>
  canvas {
    position: absolute;
    inset: 0;
    width: 100%;
    height: 100%;
  }
</style>
