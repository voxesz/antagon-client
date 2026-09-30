"""Generate the Coroa Antagon textures: the 64x32 model texture and a front-view store preview.

The model (see Cosmetics.crownModel) samples its gold boxes from the 32x16 area at (0, 0) and
its gems from the area at (0, 16), so the texture is just gold and ruby pixel noise.
"""

import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
GOLD = [(255, 214, 64), (255, 196, 36), (232, 164, 20), (255, 236, 140)]
RUBY = [(222, 22, 30), (170, 10, 18), (255, 110, 110)]
PREVIEW = [
    "k.......kk.......k",
    "yk.....kyyk.....ky",
    "yyk...kyyyyk...kyy",
    "yyyk.kyyyyyyk.kyyy",
    "yyyyyyyyyyyyyyyyyy",
    "YYYYYYYYrrYYYYYYYY",
    "YYYYYYYrRRrYYYYYYY",
    "oooooooooooooooooo",
]
COLORS = {"y": GOLD[1], "Y": GOLD[2], "o": (150, 96, 10), "r": RUBY[0], "R": RUBY[2], "k": GOLD[3]}


def texture():
    rng = random.Random(7)
    image = Image.new("RGBA", (64, 32))
    for y in range(16):
        for x in range(32):
            shade = 3 if y % 8 == 0 else rng.choices(range(3), weights=(5, 3, 2))[0]
            image.putpixel((x, y), GOLD[shade] + (255,))
    for y in range(16, 18):
        for x in range(4):
            image.putpixel((x, y), RUBY[2 if (x + y) % 3 == 0 else rng.choice((0, 1))] + (255,))
    return image


def preview():
    image = Image.new("RGBA", (len(PREVIEW[0]), len(PREVIEW)))
    for y, row in enumerate(PREVIEW):
        for x, key in enumerate(row):
            if key != ".":
                image.putpixel((x, y), COLORS[key] + (255,))
    return image


def main():
    out = ROOT / "mod-src/resources/assets/antagon"
    texture().save(out / "crown.png")
    small = preview()
    small.save(out / "crown-preview.png")
    small.resize((small.width * 12, small.height * 12), Image.Resampling.NEAREST).save(ROOT / "assets/antagon-crown.png")


if __name__ == "__main__":
    main()
