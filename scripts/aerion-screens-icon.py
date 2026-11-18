#!/usr/bin/env python3
"""Aerion Screens' icon in the Aerion logo's style (docs/brand/icon.svg): the same rounded amber
tile, with a flat monitor instead of the winged block and the logo's star.

Usage: scripts/aerion-screens-icon.py <out.png> <size>   (drawn 4x larger, then scaled down)
"""
import sys
from PIL import Image, ImageDraw

S = 4  # supersampling
N = 1024 * S


def p(*xy):
    return [v * S for v in xy]


img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
# The tile: the logo's vertical gradient from #fbbf24 to #9a4a0a, clipped to its rounded square.
grad = Image.new("RGBA", (N, N))
top, bottom = (0xFB, 0xBF, 0x24), (0x9A, 0x4A, 0x0A)
gd = ImageDraw.Draw(grad)
for y in range(N):
    t = y / (N - 1)
    gd.line([(0, y), (N, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bottom)) + (255,))
mask = Image.new("L", (N, N), 0)
ImageDraw.Draw(mask).rounded_rectangle(p(50, 50, 974, 974), radius=230 * S, fill=255)
img.paste(grad, (0, 0), mask)
d = ImageDraw.Draw(img)
WHITE, CREAM, GOLD = "#ffffff", "#fff1cf", "#ffd27a"
# The monitor: a white frame around a screen that shows the tile through it, a white play mark.
d.rounded_rectangle(p(212, 262, 812, 662), radius=48 * S, fill=WHITE)
screen = Image.new("L", (N, N), 0)
ImageDraw.Draw(screen).rounded_rectangle(p(258, 308, 766, 616), radius=18 * S, fill=255)
img.paste(grad, (0, 0), screen)
d.polygon(p(468, 392, 468, 532, 586, 462), fill=WHITE)
# Stand: a gold neck and a cream foot, like the block's shaded faces.
d.polygon(p(474, 662, 550, 662, 568, 742, 456, 742), fill=GOLD)
d.rounded_rectangle(p(360, 736, 664, 784), radius=24 * S, fill=CREAM)
# The logo's four-pointed star, over the screen's corner.
cx, cy, r, w = 800, 220, 56, 11.6
d.polygon(p(cx, cy - r, cx + w, cy - w, cx + r, cy, cx + w, cy + w, cx, cy + r, cx - w, cy + w, cx - r, cy, cx - w, cy - w), fill=WHITE)
size = int(sys.argv[2])
img.resize((size, size), Image.LANCZOS).save(sys.argv[1])
