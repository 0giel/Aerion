<script lang="ts">
  import type { Particles } from "../theme/schema";

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
  }

  $effect(() => {
    const c = canvas;
    if (!c) return;
    const ctx = c.getContext("2d");
    if (!ctx) return;
    const color = getComputedStyle(document.documentElement).getPropertyValue("--c-text").trim() || "#fff";
    let w = 0;
    let h = 0;
    let parts: P[] = [];
    const count = kind === "snow" ? 70 : kind === "dust" ? 55 : 34;

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
    });

    const resize = () => {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      w = c.clientWidth;
      h = c.clientHeight;
      c.width = Math.round(w * dpr);
      c.height = Math.round(h * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      parts = Array.from({ length: count }, () => spawn(true));
    };

    const star = (p: P, alpha: number) => {
      const r = p.r;
      ctx.globalAlpha = alpha;
      ctx.beginPath();
      ctx.moveTo(p.x, p.y - r * 2);
      ctx.quadraticCurveTo(p.x, p.y, p.x + r * 2, p.y);
      ctx.quadraticCurveTo(p.x, p.y, p.x, p.y + r * 2);
      ctx.quadraticCurveTo(p.x, p.y, p.x - r * 2, p.y);
      ctx.quadraticCurveTo(p.x, p.y, p.x, p.y - r * 2);
      ctx.fill();
    };

    let t = 0;
    const draw = () => {
      ctx.clearRect(0, 0, w, h);
      ctx.fillStyle = color;
      for (const p of parts) {
        const tw = 0.5 + 0.5 * Math.sin(t * 0.02 * p.speed + p.phase);
        if (kind === "sparkles") star(p, 0.08 + 0.32 * tw);
        else {
          ctx.globalAlpha = kind === "snow" ? 0.35 + 0.2 * tw : 0.12 + 0.25 * tw;
          ctx.beginPath();
          ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
          ctx.fill();
        }
      }
      ctx.globalAlpha = 1;
    };

    let raf = 0;
    let last = 0;
    const tick = (now: number) => {
      raf = requestAnimationFrame(tick);
      if (now - last < 33 || document.hidden) return; // ~30 fps is plenty for ambient motion
      const steps = Math.min(4, (now - last) / 16.7);
      last = now;
      t += steps;
      for (const [i, p] of parts.entries()) {
        p.x += p.vx * steps + Math.sin((t + p.phase * 50) * 0.01) * 0.08;
        p.y += p.vy * steps;
        if (p.y < -12 || p.y > h + 12 || p.x < -12 || p.x > w + 12) parts[i] = spawn(false);
      }
      draw();
    };

    resize();
    draw();
    const ro = new ResizeObserver(() => {
      resize();
      draw();
    });
    ro.observe(c);
    if (animate) raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
      ro.disconnect();
    };
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
