"""Generate the Antagon cape and its shop preview from one ASCII G design."""

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SCALE = 10
BACK = (10, 10, 110, 170)
GLYPH = (
    "  GGGGG  ",
    " GGGGGGG ",
    "GG     GG",
    "GG       ",
    "GG       ",
    "GG   GGGG",
    "GG     GG",
    "GG     GG",
    "GG     GG",
    " GGGGGGG ",
    "  GGGGG  ",
)


def font(size):
    for name in ("DejaVuSansMono-Bold.ttf", "/System/Library/Fonts/Menlo.ttc"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def main():
    texture = Image.new("RGBA", (64 * SCALE, 32 * SCALE))
    draw = ImageDraw.Draw(texture)
    # Vanilla cape UVs occupy only the first 24 x 18 units. The two broad faces
    # and their narrow sides are solid black, with no colored outline.
    draw.rectangle((0, 0, 240, 180), fill=(10, 10, 13, 255))
    draw.rectangle(BACK, fill=(12, 12, 16, 255))
    draw.rectangle((120, 10, 220, 170), fill=(8, 8, 11, 255))
    letter = font(13)
    x0, y0 = 15, 23
    for row, line in enumerate(GLYPH):
        for col, char in enumerate(line):
            if char != "G":
                continue
            x, y = x0 + col * 10, y0 + row * 12
            draw.text((x + 3, y + 4), "G", font=letter, fill=(54, 4, 12, 255))
            draw.text((x + 1, y + 2), "G", font=letter, fill=(143, 4, 22, 255))
            draw.text((x, y), "G", font=letter, fill=(246, 29, 45, 255))

    texture.save(ROOT / "mod-src/resources/assets/antagon/cape.png")
    preview = texture.crop(BACK).resize((250, 400), Image.Resampling.LANCZOS)
    preview.save(ROOT / "assets/antagon-cape.png")


if __name__ == "__main__":
    main()
