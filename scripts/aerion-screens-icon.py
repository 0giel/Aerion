#!/usr/bin/env python3
# icon.py <out.png> <size>: Aerion Screens' icon, drawn on a 32x32 pixel grid (Minecraft-like) and
# scaled up without smoothing.
import sys
from PIL import Image
N = 32
img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
px = img.load()
def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = c
# Orange tile with a lighter top and a darker bottom edge (a block, like the launcher's cards).
for y in range(N):
    t = y / (N - 1)
    c = (int(255 - 18 * t), int(158 - 40 * t), int(26 - 10 * t), 255)
    for x in range(N):
        px[x, y] = c
rect(0, 0, N - 1, 0, (255, 196, 92, 255))
rect(0, N - 2, N - 1, N - 1, (196, 92, 8, 255))
# Cut the corners so the tile reads as rounded.
for x, y in [(0, 0), (1, 0), (0, 1), (N - 1, 0), (N - 2, 0), (N - 1, 1), (0, N - 1), (1, N - 1), (0, N - 2), (N - 1, N - 1), (N - 2, N - 1), (N - 1, N - 2)]:
    px[x, y] = (0, 0, 0, 0)
DARK, EDGE = (38, 30, 46, 255), (24, 18, 30, 255)
# Monitor: frame, screen, stand.
rect(5, 6, 26, 21, DARK)
rect(5, 21, 26, 21, EDGE)
rect(6, 6, 25, 6, (64, 54, 76, 255))
# Screen: a sky over a grass block (a window showing the world).
for y in range(8, 20):
    t = (y - 8) / 11
    rect(7, y, 24, y, (int(88 + 60 * t), int(170 + 40 * t), 255, 255))
rect(7, 17, 24, 19, (106, 170, 64, 255))
rect(7, 17, 24, 17, (138, 204, 84, 255))
W = (255, 255, 255, 255)
# A play button in the middle of the screen.
for i, h in enumerate((4, 4, 3, 3, 2, 2, 1, 1, 0)):
    rect(13 + i, 13 - h, 13 + i, 13 + h, W)
# Stand and foot.
rect(14, 22, 17, 23, DARK)
rect(10, 24, 21, 25, DARK)
rect(10, 25, 21, 25, EDGE)
size = int(sys.argv[2])
img.resize((size, size), Image.NEAREST).save(sys.argv[1])
