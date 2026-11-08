// Interface effects that need the pointer: a ring where you click, and cards that lean towards
// the cursor. All of it runs only while the
// "animations and effects" setting is on (`<html data-spring="on">`) and does nothing otherwise.

const on = () => document.documentElement.dataset.spring === "on";

/** Elements a click ring appears for. */
const CLICKABLE = "button, a, [role=button], .btn, .item";

export function initFx(): () => void {
  let tilted: HTMLElement | null = null;
  let frame = 0;
  let px = 0;
  let py = 0;

  const resetTilt = () => {
    if (tilted) {
      tilted.style.transform = "";
      tilted.style.transition = "";
      tilted = null;
    }
  };

  const paint = () => {
    frame = 0;
    const card = document.elementFromPoint(px, py)?.closest<HTMLElement>("[data-tilt]") ?? null;
    if (card !== tilted) resetTilt();
    if (card) {
      const r = card.getBoundingClientRect();
      const dx = (px - r.left) / r.width - 0.5;
      const dy = (py - r.top) / r.height - 0.5;
      tilted = card;
      card.style.transition = "transform 90ms ease-out";
      card.style.transform = `perspective(700px) rotateX(${(-dy * 5).toFixed(2)}deg) rotateY(${(dx * 6).toFixed(2)}deg) translateY(-2px)`;
    }
  };

  const move = (e: PointerEvent) => {
    if (!on() || e.pointerType === "touch") return;
    px = e.clientX;
    py = e.clientY;
    if (!frame) frame = requestAnimationFrame(paint);
  };

  const down = (e: PointerEvent) => {
    if (!on() || e.button !== 0) return;
    if (!(e.target as HTMLElement | null)?.closest(CLICKABLE)) return;
    const ring = document.createElement("div");
    ring.className = "fx-ring";
    ring.style.left = `${e.clientX}px`;
    ring.style.top = `${e.clientY}px`;
    document.body.appendChild(ring);
    ring.addEventListener("animationend", () => ring.remove(), { once: true });
  };

  const leave = () => {
    resetTilt();
  };

  window.addEventListener("pointermove", move, { passive: true });
  window.addEventListener("pointerdown", down, { passive: true });
  document.documentElement.addEventListener("pointerleave", leave);
  return () => {
    window.removeEventListener("pointermove", move);
    window.removeEventListener("pointerdown", down);
    document.documentElement.removeEventListener("pointerleave", leave);
    if (frame) cancelAnimationFrame(frame);
  };
}
