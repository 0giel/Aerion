// Is this computer struggling with the launcher's effects? A short test: something with a blur
// is moved over a changing picture for about a second and the time between frames is measured.
// It only ever leads to a suggestion (lite mode), never to a change by itself.

/** Average milliseconds per frame (60 Hz is ~16.7), or null when the test couldn't run fairly. */
export async function measureFrames(): Promise<number | null> {
  // A hidden or unfocused window gets its frames throttled, which would read as "slow".
  if (document.visibilityState !== "visible" || !document.hasFocus()) return null;
  const host = document.createElement("div");
  host.setAttribute("aria-hidden", "true");
  host.style.cssText =
    "position:fixed;left:0;top:0;width:70vw;height:70vh;opacity:0.02;pointer-events:none;z-index:-1;overflow:hidden;";
  host.innerHTML =
    '<div style="position:absolute;inset:0;background:linear-gradient(110deg,#f33,#3f3,#33f,#f33);background-size:300% 100%"></div>' +
    '<div style="position:absolute;inset:15%;backdrop-filter:blur(18px);background:rgba(255,255,255,.2);border-radius:16px"></div>';
  document.body.appendChild(host);
  const moving = host.firstElementChild as HTMLElement;
  const glass = host.lastElementChild as HTMLElement;
  const samples: number[] = [];
  let last = 0;
  const started = performance.now();
  await new Promise<void>((resolve) => {
    const tick = (now: number) => {
      const t = now - started;
      // Both a repaint (the picture) and a moving blur, like the real interface.
      moving.style.backgroundPosition = `${(t / 6) % 300}% 0`;
      glass.style.transform = `translateX(${Math.sin(t / 200) * 40}px)`;
      if (last) samples.push(now - last);
      last = now;
      if (t < 1300 && document.visibilityState === "visible") requestAnimationFrame(tick);
      else resolve();
    };
    requestAnimationFrame(tick);
  });
  host.remove();
  // The first frames include setup; long gaps are the window being busy, not slow rendering.
  const fair = samples.slice(5).filter((ms) => ms < 250);
  if (fair.length < 30) return null;
  return fair.reduce((a, b) => a + b, 0) / fair.length;
}

/** Slower than this (about 38 fps) counts as struggling. */
export const SLOW_MS = 26;
