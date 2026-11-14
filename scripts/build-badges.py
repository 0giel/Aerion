#!/usr/bin/env python3
"""Draw the badge pictures of the Aerion mod's badge font.

    python3 scripts/build-badges.py        (needs: pip install cairosvg pillow)

Writes mod/common/src/main/resources/aerionpack/assets/aerion/textures/font/badges.png: one square
picture per badge, left to right in the order of the font's characters U+E000... (see
assets/aerion/font/badges.json). Each badge is a coloured block with a darker lower edge, the
launcher's own shape, with an icon on it (Phosphor, MIT; the bug net from game-icons.net,
CC BY 3.0). Commit the result.
"""

import io
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

# Pixels per badge. The game draws font pictures without smoothing, so far more than the size it
# shows them at only adds shimmer; 48 stays sharp from small to large interface scales.
CELL = 48
OUT = Path(__file__).resolve().parent.parent / "mod/common/src/main/resources/aerionpack/assets/aerion/textures/font/badges.png"

# Icons as (view box size, path). Phosphor Icons (fill, 256), MIT: https://github.com/phosphor-icons/core
ICONS = {
    "star": (256, "M234.29,114.85l-45,38.83L203,211.75a16.4,16.4,0,0,1-24.5,17.82L128,198.49,77.47,229.57A16.4,16.4,0,0,1,53,211.75l13.76-58.07-45-38.83A16.46,16.46,0,0,1,31.08,86l59-4.76,22.76-55.08a16.36,16.36,0,0,1,30.27,0l22.75,55.08,59,4.76a16.46,16.46,0,0,1,9.37,28.86Z"),
    "flask": (256, "M221.69,199.77,160,96.92V40h8a8,8,0,0,0,0-16H88a8,8,0,0,0,0,16h8V96.92L34.31,199.77A16,16,0,0,0,48,224H208a16,16,0,0,0,13.72-24.23Zm-90.08-42.91c-15.91-8.05-31.05-12.32-45.22-12.81l24.47-40.8A7.93,7.93,0,0,0,112,99.14V40h32V99.14a7.93,7.93,0,0,0,1.14,4.11L183.36,167C171.4,169.34,154.29,168.34,131.61,156.86Z"),
    "heart": (256, "M240,102c0,70-103.79,126.66-108.21,129a8,8,0,0,1-7.58,0C119.79,228.66,16,172,16,102A62.07,62.07,0,0,1,78,40c20.65,0,38.73,8.88,50,23.89C139.27,48.88,157.35,40,178,40A62.07,62.07,0,0,1,240,102Z"),
    # "Bug net" by Delapouite, game-icons.net, CC BY 3.0: https://game-icons.net/1x1/delapouite/bug-net.html
    "net": (512, "M370.3 20.21c-22.9 0-45.2 2.68-66.8 7.52l-1-1.26c-.8.69-1.7 1.38-2.5 2.07-72.4 17.15-135.7 58.78-180.7 105.96-33.69 35.4-57.38 73.9-67.03 108.9-4.82 17.4-6.14 34.1-2.89 49.2 3.26 15 11.42 28.3 24.43 37.5 6.26 4.4 13.9 5.1 21.3 4.2 7.39-.9 15.09-3.4 23.39-6.9 16.7-6.8 35.9-17.5 56.6-28.2 39.3-20.3 83.7-40 121-36l-1.2-.6c-6.9-3.9-11.4-10.3-14-17.9-14.8.5-29.7 3.6-44.4 8.1-4.8-11.4-8.5-22.4-10.9-33.3 17.6-7.1 35.7-13.4 54.3-19 1.2-6.5 2.8-13.3 4.8-20.3-21.3 6.1-41.9 13.1-61.9 21-1.5-16.3.1-32.4 5-48.5 26-10.3 52.4-19.3 78.8-26.2 1.9-3.7 4-7.5 6.1-11.2 2-3.5 4-7 6.1-10.3-27.7 6.3-55 14.8-82 24.8 6.3-12.7 14.8-25.7 25.7-38.97 25.7-5.86 51.4-9.86 73.5-11.41 5.1-6.76 10.2-13.06 15.4-18.83-20.3.18-43.8 2.96-68.2 7.52 7.5-7.51 15.8-15.13 24.9-22.87 20.1-4.49 40.9-6.98 62.2-7.01 1.5 0 3 0 4.4.1 9-7.06 17.9-12.35 26.5-15.35.9-.3 1.8-.57 2.7-.82-11.3-1.29-22.6-1.91-33.6-1.91zm63 10.04c-4.8-.1-9.8.86-14.7 2.54-7.9 2.73-15.9 7.37-24 13.5-3.7 2.33-7.5 5.14-11.5 8.46-17.8 14.84-37.8 39.39-54.7 69.35-17 30-27.7 59.7-31.3 82.7-.8 5.1-1.2 9.9-1.4 14.2-1 10.1-.8 19.4.9 27.5 1.5 6.9 4.2 13.3 8.6 18.2L182.8 483l15.6 8.8 122.4-216.3c6.5 1.2 13.4.3 20-2 9.7-3.3 19.6-9.5 29.7-17.9 20.1-16.9 41-42.8 58.8-74.4 17.9-31.5 29.4-62.8 33.5-88.74 2-12.89 2.2-24.61.1-34.66-2.2-10.05-7.1-19.03-15.7-23.91-4.3-2.43-9-3.55-13.9-3.64zm-.5 17.94c2.2 0 4.1.53 5.5 1.37 3 1.67 5.5 5.16 7 12 1.4 6.88 1.4 16.64-.4 28.15-3.5 22.89-14.3 52.69-31.2 82.69-17 29.9-36.9 54.5-54.8 69.4-8.9 7.4-17.3 12.4-23.9 14.7-6.7 2.3-10.9 1.9-13.9.2-2.9-1.6-5.5-5.1-7-12-1.4-6.8-1.4-16.6.4-28.1 3.6-22.9 14.3-52.7 31.3-82.6 16.9-30 36.9-54.56 54.7-69.41 9-7.47 17.3-12.5 23.9-14.85 3.4-1.1 6.1-1.57 8.4-1.55zm-163.5 8.42c-6 6.04-11.6 12.05-16.7 18.05-16.5 3.95-32.8 8.55-48.1 13.68 20.1-12.54 41.8-23.38 64.8-31.73zm-34.6 41.34c-9.2 13.65-16.3 27.35-21.3 41.05-24.7 10-48.9 21.2-72.6 32.9 4.4-14.2 10.8-29.4 19.5-45.8 19.6-10.2 46.2-20.1 74.4-28.15zM129.3 150.1c-4.4 11.3-7.7 22.2-10 32.7-8.7 4.5-17.3 9-25.73 13.5 9.63-15.4 21.73-31 35.73-46.2zm77.9 11c-3.2 15.7-3.6 31.4-1.6 47.3-24.2 10.4-47.4 22-70 34.8-1.4-7.1-2.3-14.5-2.4-22.2-.1-8.3.5-17 2.1-26.1 23.4-12 47.5-23.4 71.9-33.8zm-91.4 43.8c-.5 5.6-.7 11.1-.6 16.4.2 11 1.7 21.4 4.2 31.4-18 10.8-35.58 22.4-52.73 34.5-2.05-10.9-1.11-24.3 2.96-39.1 2.01-7.3 4.79-14.9 8.28-22.7 12.43-6.9 25.09-13.7 37.89-20.5zm93.1 21.6c2.5 10.7 6.1 21.4 10.7 32.2-18.4 7.1-36.2 16-52.8 24.5-3.7 1.9-7.3 3.8-10.8 5.7-6.3-8.9-11.4-18.1-15.1-27.9 21.9-12.7 44.5-24.2 68-34.5zm-83.6 43.7c3.9 9.5 8.8 18.5 14.6 27-10.4 5.4-19.9 10.2-28.2 13.6-7.5 3.1-14.03 5.1-18.8 5.7-4.77.6-7.27-.1-8.71-1.2-4.43-3.1-7.97-6.7-10.73-10.8 16.87-12.1 34.04-23.6 51.84-34.3z"),
}

# In font order: creator, bugHunter, tester, contributor.
BADGES = [
    ("star", (0xFF, 0xC8, 0x3D)),
    ("net", (0x5B, 0xE3, 0x7A)),
    ("flask", (0x55, 0xD6, 0xFF)),
    ("heart", (0xFF, 0x6F, 0xA8)),
]


# Thin line icons get a stroke of their own colour (in view box units) and more room, so they hold
# up at the small size the game shows them at.
BOLD = {"net": (22, 0.8)}


def icon(name: str, size: int, rgb: tuple) -> Image.Image:
    fill = "#%02x%02x%02x" % rgb
    box, path = ICONS[name]
    stroke = f' stroke="{fill}" stroke-width="{BOLD[name][0]}" stroke-linejoin="round"' if name in BOLD else ""
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {box} {box}"><path fill="{fill}"{stroke} d="{path}"/></svg>'
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=size, output_height=size)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def badge(name: str, rgb: tuple) -> Image.Image:
    n = CELL * 4  # drawn large, then scaled down smoothly
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    radius, edge = int(n * 0.2), int(n * 0.09)
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((0, 0, n - 1, n - 1), radius, fill=tuple(int(c * 0.62) for c in rgb) + (255,))
    d.rounded_rectangle((0, 0, n - 1, n - 1 - edge), radius, fill=rgb + (255,))
    # A soft light on the upper half, so the block isn't flat.
    light = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    ImageDraw.Draw(light).rounded_rectangle((0, 0, n - 1, (n - edge) // 2), radius, fill=(255, 255, 255, 34))
    mask = Image.new("L", (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, n - 1, n - 1 - edge), radius, fill=255)
    im.alpha_composite(Image.composite(light, Image.new("RGBA", (n, n), (0, 0, 0, 0)), mask))
    # The icon in a dark shade of the badge colour, like text on the launcher's accent buttons.
    ic = icon(name, int(n * (BOLD[name][1] if name in BOLD else 0.62)), tuple(int(c * 0.22) for c in rgb))
    im.alpha_composite(ic, ((n - ic.width) // 2, (n - edge - ic.height) // 2))
    return im.resize((CELL, CELL), Image.LANCZOS)


def main() -> None:
    strip = Image.new("RGBA", (CELL * len(BADGES), CELL), (0, 0, 0, 0))
    for i, (name, rgb) in enumerate(BADGES):
        strip.paste(badge(name, rgb), (i * CELL, 0))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    strip.save(OUT, optimize=True)
    print(OUT)


if __name__ == "__main__":
    main()
