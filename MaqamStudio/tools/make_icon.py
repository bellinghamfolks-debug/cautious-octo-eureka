#!/usr/bin/env python3
"""Draws the Maqam Studio app icon (1024 x 1024, opaque) into the asset catalog.

A gold waveform whose bar heights follow maqam Bayati's degrees (in cents) on a
deep indigo field. Reproducible: run it again after changing the design.
Requires Pillow.
"""
from __future__ import annotations

import math
import pathlib

from PIL import Image, ImageDraw, ImageFilter

SIZE = 1024
OUT = (pathlib.Path(__file__).resolve().parent.parent
       / "App" / "Resources" / "Assets.xcassets" / "AppIcon.appiconset" / "AppIcon-1024.png")

TOP = (24, 22, 64)
BOTTOM = (9, 46, 66)
GOLD = (236, 188, 92)
GOLD_LIGHT = (255, 223, 150)
# Bayati, rising and falling: 0 150 300 500 700 800 1000 1200 ... back down.
DEGREES = [0, 150, 300, 500, 700, 800, 1000, 1200, 1000, 800, 700, 500, 300, 150, 0]


def main() -> None:
    image = Image.new("RGB", (SIZE, SIZE))
    pixels = image.load()
    for y in range(SIZE):
        t = y / (SIZE - 1)
        row = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
        for x in range(SIZE):
            pixels[x, y] = row

    # A soft disc behind the waveform.
    disc = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(disc).ellipse((152, 152, 872, 872), fill=90)
    disc = disc.filter(ImageFilter.GaussianBlur(60))
    image = Image.composite(Image.new("RGB", (SIZE, SIZE), (92, 80, 170)), image, disc)

    draw = ImageDraw.Draw(image)
    count = len(DEGREES)
    width = 34
    gap = (680 - count * width) / (count - 1)
    left = (SIZE - 680) / 2
    middle = SIZE / 2
    for index, cents in enumerate(DEGREES):
        height = 90 + cents / 1200 * 420
        # A slight vibrato so the shape reads as voice, not a bar chart.
        height += 18 * math.sin(index * 1.3)
        x = left + index * (width + gap)
        colour = GOLD_LIGHT if cents in (0, 1200) else GOLD
        draw.rounded_rectangle((x, middle - height / 2, x + width, middle + height / 2),
                               radius=width / 2, fill=colour)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUT, "PNG", optimize=True)
    print(OUT)


if __name__ == "__main__":
    main()
