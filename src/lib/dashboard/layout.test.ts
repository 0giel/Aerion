import { describe, expect, it } from "vitest";
import { clamp, compact, freeSpot, place, type Box } from "./layout";

const b = (id: string, x: number, y: number, w: number, h: number): Box => ({ id, x, y, w, h });
const noOverlap = (list: Box[]) =>
  list.every((a) =>
    list.every((c) => a === c || !(a.x < c.x + c.w && c.x < a.x + a.w && a.y < c.y + c.h && c.y < a.y + a.h)),
  );

describe("dashboard layout", () => {
  it("keeps boxes inside the grid", () => {
    expect(clamp(b("a", 10, -2, 6, 0))).toEqual(b("a", 6, 0, 6, 1));
  });

  it("floats boxes up into free space", () => {
    const out = compact([b("a", 0, 5, 4, 2), b("b", 4, 9, 4, 2)]);
    expect(out.map((x) => x.y)).toEqual([0, 0]);
  });

  it("pushes covered boxes down and never overlaps", () => {
    const board = [b("a", 0, 0, 6, 2), b("b", 6, 0, 6, 2), b("c", 0, 2, 12, 2)];
    const out = place(board, b("c", 0, 0, 12, 2));
    expect(noOverlap(out)).toBe(true);
    expect(out.find((x) => x.id === "c")?.y).toBe(0);
    expect(out.find((x) => x.id === "a")?.y).toBe(2);
  });

  it("finds a free spot for a new widget", () => {
    expect(freeSpot([b("a", 0, 0, 8, 2)], 4, 2)).toEqual({ x: 8, y: 0 });
    expect(freeSpot([b("a", 0, 0, 12, 2)], 4, 2)).toEqual({ x: 0, y: 2 });
  });
});
