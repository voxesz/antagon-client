"""Generate the high-resolution "Capa Logo Antagon" texture and its store preview.

Vanilla cape UVs are laid out on a 64x32 grid; the texture here is the same grid at
16x (1024x512), which Minecraft samples proportionally. The outer face, seen from
behind the player, is the 10x16 area starting at (1, 1).
"""

import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
S = 16
RED = (238, 21, 21, 255)
RED_DARK = (170, 12, 12, 255)
RED_INNER = (120, 8, 8, 255)
OUTER = (1 * S, 1 * S, 11 * S, 17 * S)


def mark(svg, width):
    with tempfile.TemporaryDirectory() as tmp:
        big = Path(tmp) / "g.png"
        subprocess.run(["magick", "-background", "none", "-density", "1200", str(svg), str(big)], check=True)
        image = Image.open(big).convert("RGBA")
    image = image.crop(image.getbbox())
    image = image.crop((0, int(image.height * 0.05), int(image.width * 0.93), image.height))
    image = image.crop(image.getbbox())
    return image.resize((width, round(image.height * width / image.width)), Image.Resampling.LANCZOS)


def main():
    texture = Image.new("RGBA", (64 * S, 32 * S))
    draw = ImageDraw.Draw(texture)
    draw.rectangle((0, 0, 22 * S - 1, 17 * S - 1), fill=RED_DARK)
    draw.rectangle((OUTER[0], OUTER[1], OUTER[2] - 1, OUTER[3] - 1), fill=RED)
    draw.rectangle((12 * S, 1 * S, 22 * S - 1, 17 * S - 1), fill=RED_INNER)

    logo = mark(ROOT / "assets/g-light.svg", int(7.4 * S))
    x = OUTER[0] + (OUTER[2] - OUTER[0] - logo.width) // 2
    y = OUTER[1] + int(3.2 * S)
    shadow = Image.new("RGBA", logo.size, (90, 0, 0, 170))
    shadow.putalpha(logo.getchannel("A").point(lambda a: a * 170 // 255))
    texture.alpha_composite(shadow, (x + S // 4, y + S // 4))
    texture.alpha_composite(logo, (x, y))

    out = ROOT / "mod-src/resources/assets/antagon/logo-cape.png"
    texture.save(out)
    texture.crop(OUTER).resize((250, 400), Image.Resampling.LANCZOS).save(ROOT / "assets/antagon-logo-cape.png")


if __name__ == "__main__":
    main()
