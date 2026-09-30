"""Make a transparent in-game button icon from the launcher logo."""

from pathlib import Path
from PIL import Image

root = Path(__file__).resolve().parents[1]
source = Image.open(root / "assets/icon.png").convert("RGBA")
pixels = source.load()
for y in range(source.height):
    for x in range(source.width):
        r, g, b, _ = pixels[x, y]
        if r < 70 or r <= g * 1.5:
            pixels[x, y] = (0, 0, 0, 0)
bounds = source.getbbox()
logo = source.crop(bounds).resize((64, 64), Image.Resampling.LANCZOS)
logo.save(root / "mod-src/resources/assets/antagon/logo.png")
