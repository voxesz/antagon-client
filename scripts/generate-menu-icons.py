"""Generate the 16x16 pause-menu icons as Minecraft-style pixel art.

Each icon is drawn 1:1 (one texture pixel per GUI pixel) inside a 20x20 vanilla
button, with a 2px transparent margin so the art never touches the button edge.
"""

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "mod-src/resources/assets/antagon"

PALETTE = {
    ".": (0, 0, 0, 0),
    "k": (30, 30, 30, 255),
    "r": (238, 21, 21, 255),
    "R": (160, 12, 12, 255),
    "w": (240, 238, 232, 255),
    "g": (160, 160, 160, 255),
    "y": (255, 210, 63, 255),
    "Y": (200, 140, 20, 255),
    "o": (120, 80, 10, 255),
}

CHAT = [
    "................",
    "................",
    "..kkkkkkkkkkkk..",
    ".kwwwwwwwwwwwwk.",
    ".kwwwwwwwwwwwwk.",
    ".kwwgwwgwwgwwwk.",
    ".kwwgwwgwwgwwwk.",
    ".kwwwwwwwwwwwwk.",
    ".kwwwwwwwwwwwwk.",
    "..kkkwwkkkkkkk..",
    "....kwwk........",
    "....kwk.........",
    "....kk..........",
    "................",
    "................",
    "................",
]

COIN = [
    "................",
    "................",
    ".....kkkkkk.....",
    "....kyyyyyyk....",
    "...kyyYYYYyyk...",
    "..kyyYyyyyYyyk..",
    "..kyYyyyyyyYyk..",
    "..kyYyyooyyYyk..",
    "..kyYyyooyyYyk..",
    "..kyYyyyyyyYyk..",
    "..kyyYyyyyYyyk..",
    "...kyyYYYYyyk...",
    "....kyyyyyyk....",
    ".....kkkkkk.....",
    "................",
    "................",
]

CROWN = [
    "................",
    "................",
    "................",
    "..k....kk....k..",
    ".kyk..kyyk..kyk.",
    ".kyyk.kyyk.kyyk.",
    ".kyyykyyyykyyyk.",
    ".kyyyyyyyyyyyyk.",
    ".kyyryyyyyyryyk.",
    ".kyyyyyrryyyyyk.",
    ".kYYYYYYYYYYYYk.",
    ".kYYYYYYYYYYYYk.",
    "..kkkkkkkkkkkk..",
    "................",
    "................",
    "................",
]


def draw(rows):
    image = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            image.putpixel((x, y), PALETTE[key])
    return image


G_MARK = [
    "..########..",
    ".##########.",
    "############",
    "###.....####",
    "........####",
    "#####....###",
    "#####....###",
    "###.....####",
    "############",
    "###.#######.",
]


def logo():
    """The Antagon G mark, hand-drawn at 12x10 from the brand shape, red with a dark outline."""
    image = Image.new("RGBA", (16, 16))
    for y, row in enumerate(G_MARK):
        for x, key in enumerate(row):
            if key == "#":
                below = y + 1 >= len(G_MARK) or G_MARK[y + 1][x] != "#"
                image.putpixel((x + 2, y + 3), PALETTE["R"] if below else PALETTE["r"])
    outline = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            if image.getpixel((x, y))[3]:
                continue
            near = any(
                0 <= x + dx < 16 and 0 <= y + dy < 16 and image.getpixel((x + dx, y + dy))[3]
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))
            )
            if near:
                outline.putpixel((x, y), PALETTE["k"])
    return Image.alpha_composite(outline, image)


TAB_MARK = [
    ".######.",
    "########",
    "##....##",
    ".....###",
    "####..##",
    "##....##",
    "########",
    "##.####.",
]


def tab_logo():
    image = Image.new("RGBA", (8, 8))
    for y, row in enumerate(TAB_MARK):
        for x, key in enumerate(row):
            if key == "#":
                below = y + 1 >= len(TAB_MARK) or TAB_MARK[y + 1][x] != "#"
                image.putpixel((x, y), PALETTE["R"] if below else PALETTE["r"])
    return image


def main():
    logo().save(OUT / "logo.png")
    tab_logo().save(OUT / "tab-logo.png")
    draw(CHAT).save(OUT / "chat.png")
    draw(COIN).save(OUT / "store.png")
    draw(CROWN).save(OUT / "admin.png")


if __name__ == "__main__":
    main()
