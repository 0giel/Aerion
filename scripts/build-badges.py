#!/usr/bin/env python3
"""Draw the badge pictures of the Aerion mod's badge font.

    python3 scripts/build-badges.py        (needs: pip install cairosvg pillow)

Writes mod/common/src/main/resources/aerionpack/assets/aerion/textures/font/badges.png: one square
picture per badge, left to right in the order of the font's characters U+E000... (see
assets/aerion/font/badges.json). Each badge is a coloured block with a darker lower edge, the
launcher's own shape, with a Phosphor icon (fill weight, MIT) on it. Commit the result.
"""

import io
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw

# Pixels per badge. The game draws font pictures without smoothing, so far more than the size it
# shows them at only adds shimmer; 48 stays sharp from small to large interface scales.
CELL = 48
OUT = Path(__file__).resolve().parent.parent / "mod/common/src/main/resources/aerionpack/assets/aerion/textures/font/badges.png"

# Phosphor Icons (fill), 256 x 256, MIT: https://github.com/phosphor-icons/core
ICONS = {
    "star": "M234.29,114.85l-45,38.83L203,211.75a16.4,16.4,0,0,1-24.5,17.82L128,198.49,77.47,229.57A16.4,16.4,0,0,1,53,211.75l13.76-58.07-45-38.83A16.46,16.46,0,0,1,31.08,86l59-4.76,22.76-55.08a16.36,16.36,0,0,1,30.27,0l22.75,55.08,59,4.76a16.46,16.46,0,0,1,9.37,28.86Z",
    "bug": "M168,92a12,12,0,1,1-12-12A12,12,0,0,1,168,92ZM100,80a12,12,0,1,0,12,12A12,12,0,0,0,100,80Zm116,64A87.76,87.76,0,0,1,213,167l22.24,9.72A8,8,0,0,1,232,192a7.89,7.89,0,0,1-3.2-.67L207.38,182a88,88,0,0,1-158.76,0L27.2,191.33A7.89,7.89,0,0,1,24,192a8,8,0,0,1-3.2-15.33L43,167A87.76,87.76,0,0,1,40,144v-8H16a8,8,0,0,1,0-16H40v-8a87.76,87.76,0,0,1,3-23L20.8,79.33a8,8,0,1,1,6.4-14.66L48.62,74a88,88,0,0,1,158.76,0l21.42-9.36a8,8,0,0,1,6.4,14.66L213,89.05a87.76,87.76,0,0,1,3,23v8h24a8,8,0,0,1,0,16H216Zm-80,0a8,8,0,0,0-16,0v64a8,8,0,0,0,16,0Zm64-32a72,72,0,0,0-144,0v8H200Z",
    "flask": "M221.69,199.77,160,96.92V40h8a8,8,0,0,0,0-16H88a8,8,0,0,0,0,16h8V96.92L34.31,199.77A16,16,0,0,0,48,224H208a16,16,0,0,0,13.72-24.23Zm-90.08-42.91c-15.91-8.05-31.05-12.32-45.22-12.81l24.47-40.8A7.93,7.93,0,0,0,112,99.14V40h32V99.14a7.93,7.93,0,0,0,1.14,4.11L183.36,167C171.4,169.34,154.29,168.34,131.61,156.86Z",
    "heart": "M240,102c0,70-103.79,126.66-108.21,129a8,8,0,0,1-7.58,0C119.79,228.66,16,172,16,102A62.07,62.07,0,0,1,78,40c20.65,0,38.73,8.88,50,23.89C139.27,48.88,157.35,40,178,40A62.07,62.07,0,0,1,240,102Z",
}

# In font order: creator, bugHunter, tester, contributor.
BADGES = [
    ("star", (0xFF, 0xC8, 0x3D)),
    ("bug", (0x5B, 0xE3, 0x7A)),
    ("flask", (0x55, 0xD6, 0xFF)),
    ("heart", (0xFF, 0x6F, 0xA8)),
]


def icon(name: str, size: int, rgb: tuple) -> Image.Image:
    fill = "#%02x%02x%02x" % rgb
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256"><path fill="{fill}" d="{ICONS[name]}"/></svg>'
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
    ic = icon(name, int(n * 0.62), tuple(int(c * 0.22) for c in rgb))
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
