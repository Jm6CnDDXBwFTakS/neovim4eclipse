#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Jm6CnDDXBwFTakS
# SPDX-License-Identifier: EPL-2.0
"""Renders an SVG to a PNG icon with a transparent background, using only headless Firefox and Pillow.

    releng/render-icon.py [--grey] <svg> <size> <out.png> [<size> <out.png> ...]

Example, the toolbar icon and its HiDPI variant (Eclipse picks name@2x.png on its own when the
screen scale is 200%):

    releng/render-icon.py docs/images/logo.svg \
        16 plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/icons/neovim.png \
        32 plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/icons/neovim@2x.png

With --grey the icons are greyscale: the toolbar button shows them in Eclipse mode, so that the
mode is visible where a pressed toggle button is hard to tell from a raised one (macOS):

    releng/render-icon.py --grey docs/images/logo.svg \
        16 plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/icons/neovim-off.png \
        32 plugins/io.github.jm6cnddxbwftaks.neovim4eclipse/icons/neovim-off@2x.png

Why this way: no SVG renderer (rsvg-convert, Inkscape, ImageMagick) is assumed to be installed, but
Firefox is. Firefox's --screenshot has no transparency, so the SVG is rendered twice, once on black
and once on white, and the transparency is recovered from the difference ("difference matting"):
a pixel the drawing covers fully looks the same on both, an uncovered one is black on one and
white on the other, and a half-covered edge pixel lies in between:

    alpha = 1 - (on_white - on_black) / 255        color = on_black / alpha

The large rendering (512 px) is then scaled down with Lanczos filtering on premultiplied colors,
so the small icon gets smooth edges without dark fringes.
"""

import os
import subprocess
import sys
import tempfile

from PIL import Image

BIG = 512


def render(svg, background, directory):
    """Screenshots `svg` at BIG x BIG on `background` with headless Firefox; returns an RGB image."""
    page = os.path.join(directory, f"page-{background.strip('#')}.html")
    shot = os.path.join(directory, f"shot-{background.strip('#')}.png")
    with open(page, "w", encoding="utf-8") as f:
        f.write(f'<body style="margin:0;background:{background}">'
                f'<img src="file://{os.path.abspath(svg)}" width="{BIG}" height="{BIG}" style="display:block">')
    # A profile of its own, so a running Firefox (the user's) is neither used nor disturbed.
    profile = os.path.join(directory, "profile")
    os.makedirs(profile, exist_ok=True)
    subprocess.run(["firefox", "--headless", "--no-remote", "--profile", profile,
                    f"--window-size={BIG},{BIG}", "--screenshot", shot, f"file://{page}"],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return Image.open(shot).convert("RGB").crop((0, 0, BIG, BIG))


def matte(on_black, on_white):
    """The RGBA image whose composite on black and on white gives the two renderings."""
    out = Image.new("RGBA", on_black.size)
    black, white = on_black.load(), on_white.load()
    pixels = out.load()
    for y in range(on_black.height):
        for x in range(on_black.width):
            b, w = black[x, y], white[x, y]
            # Average the three channels' differences; they agree up to rounding.
            alpha = 255 - sum(wc - bc for bc, wc in zip(b, w)) / 3
            alpha = max(0.0, min(255.0, alpha))
            if alpha < 1:
                pixels[x, y] = (0, 0, 0, 0)
            else:
                pixels[x, y] = tuple(min(255, round(c * 255 / alpha)) for c in b) + (round(alpha),)
    return out


def shrink(image, size):
    """Scales an RGBA image down without dark fringes: Pillow's "RGBa" mode is premultiplied."""
    return image.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


def grey(image):
    """The same icon in shades of grey, its transparency kept.

    Uses the luminance (ITU-R 601: 0.299 R + 0.587 G + 0.114 B, Pillow's "L" mode), so that the
    logo's light and dark parts stay light and dark, and the shape stays recognizable. Eclipse's
    own greyed-out (disabled) look is paler still, so the two do not get confused.
    """
    alpha = image.getchannel("A")
    out = image.convert("L").convert("RGBA")
    out.putalpha(alpha)
    return out


def main(argv):
    greyscale = len(argv) > 1 and argv[1] == "--grey"
    if greyscale:
        argv = argv[:1] + argv[2:]
    if len(argv) < 4 or len(argv) % 2 != 0:
        sys.exit(__doc__)
    svg, targets = argv[1], list(zip(argv[2::2], argv[3::2]))
    with tempfile.TemporaryDirectory() as directory:
        icon = matte(render(svg, "#000000", directory), render(svg, "#ffffff", directory))
    for size, out in targets:
        os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
        small = shrink(icon, int(size))
        (grey(small) if greyscale else small).save(out, optimize=True)
        print(f"{out}: {size}x{size}")


if __name__ == "__main__":
    main(sys.argv)
