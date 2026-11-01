// Placement on a 12-column dashboard grid: widgets never overlap, and after every change they
// float up into free space (like a gravity-packed board), so the page has no holes.
export const COLS = 12;

export interface Box {
  id: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

const overlaps = (a: Box, b: Box) =>
  a.id !== b.id && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

/** Keep a box inside the grid. */
export function clamp<T extends Box>(b: T): T {
  const w = Math.min(COLS, Math.max(1, Math.round(b.w)));
  const h = Math.min(12, Math.max(1, Math.round(b.h)));
  return { ...b, w, h, x: Math.min(COLS - w, Math.max(0, Math.round(b.x))), y: Math.max(0, Math.round(b.y)) };
}

/** Move every box up as far as it goes, top to bottom; `pinned` keeps its row. */
export function compact<T extends Box>(boxes: T[], pinned?: string): T[] {
  const sorted = [...boxes].sort((a, b) => a.y - b.y || a.x - b.x);
  const placed: T[] = [];
  for (const b of sorted) {
    const cur = { ...b };
    if (b.id !== pinned) {
      while (cur.y > 0 && !placed.some((p) => overlaps({ ...cur, y: cur.y - 1 }, p))) cur.y--;
    }
    // Something pinned above may still collide: push down below it.
    while (placed.some((p) => overlaps(cur, p))) cur.y++;
    placed.push(cur);
  }
  return placed;
}

/** Put `moved` at its new place; whatever it covers is pushed down, then all float up. */
export function place<T extends Box>(boxes: T[], moved: T): T[] {
  const m = clamp(moved);
  const others = boxes.filter((b) => b.id !== m.id);
  // The moved box goes first so it keeps its spot; others make room under it.
  const ordered = [m, ...others.sort((a, b) => a.y - b.y || a.x - b.x)];
  const placed: T[] = [];
  for (const b of ordered) {
    const cur = { ...b };
    while (placed.some((p) => overlaps(cur, p))) cur.y++;
    placed.push(cur);
  }
  return compact(placed);
}

/** The first free spot for a new box of size `w`×`h`, scanning rows top to bottom. */
export function freeSpot(boxes: Box[], w: number, h: number): { x: number; y: number } {
  for (let y = 0; y < 400; y++) {
    for (let x = 0; x + w <= COLS; x++) {
      const probe = { id: "\u0000", x, y, w, h };
      if (!boxes.some((b) => overlaps(probe, b))) return { x, y };
    }
  }
  return { x: 0, y: Math.max(0, ...boxes.map((b) => b.y + b.h)) };
}

/** Rows the board needs. */
export const rows = (boxes: Box[]) => Math.max(0, ...boxes.map((b) => b.y + b.h));
