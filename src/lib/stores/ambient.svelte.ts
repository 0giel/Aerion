// Ambient animations (particles, the player model, background video) run only while someone is
// using the launcher: window visible and focused, input within the last 30 s. Without GPU
// compositing (common with WebKitGTK) every animated frame repaints the window, so a launcher
// left open behind the game must cost nothing.
const IDLE_MS = 30_000;

class Ambient {
  active = $state(false);
  private timer: ReturnType<typeof setTimeout> | undefined;
  private lastInput = 0;

  init() {
    const set = (focused: boolean) => {
      clearTimeout(this.timer);
      this.active = focused && !document.hidden;
      if (this.active) this.timer = setTimeout(() => (this.active = false), IDLE_MS);
    };
    const input = () => {
      const now = performance.now();
      // Re-arming the timer once a second is enough.
      if (this.active && now - this.lastInput < 1000) return;
      this.lastInput = now;
      set(true);
    };
    document.addEventListener("visibilitychange", () => set(document.hasFocus()));
    window.addEventListener("focus", () => set(true));
    window.addEventListener("blur", () => set(false));
    for (const ev of ["pointermove", "pointerdown", "keydown", "wheel"])
      window.addEventListener(ev, input, { passive: true });
    set(document.hasFocus());
  }
}

export const ambient = new Ambient();

// One shared frame clock for all ambient animations: without GPU compositing each repaint costs
// a full-window redraw, so animations must land in the same frames instead of interleaving.
const FRAME_MS = 50; // 20 fps
// eslint-disable-next-line svelte/prefer-svelte-reactivity -- plain callback registry, not UI state
const subscribers = new Set<(dt: number) => void>();
let raf = 0;
let last = 0;

function tick(now: number) {
  raf = subscribers.size ? requestAnimationFrame(tick) : 0;
  if (now - last < FRAME_MS) return;
  const dt = Math.min(200, now - last);
  last = now;
  for (const f of subscribers) f(dt);
}

/** Call `f(elapsedMs)` on the shared ~20 fps clock until the returned function is called. */
export function onAmbientFrame(f: (dt: number) => void): () => void {
  subscribers.add(f);
  if (!raf) {
    last = performance.now();
    raf = requestAnimationFrame(tick);
  }
  return () => {
    subscribers.delete(f);
    if (!subscribers.size && raf) {
      cancelAnimationFrame(raf);
      raf = 0;
    }
  };
}
