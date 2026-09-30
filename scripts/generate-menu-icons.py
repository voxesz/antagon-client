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


def tab_logo(light="r", dark="R"):
    """Red for players on the client, gold for admins."""
    image = Image.new("RGBA", (8, 8))
    for y, row in enumerate(TAB_MARK):
        for x, key in enumerate(row):
            if key == "#":
                below = y + 1 >= len(TAB_MARK) or TAB_MARK[y + 1][x] != "#"
                image.putpixel((x, y), PALETTE[dark] if below else PALETTE[light])
    return image


COIN_MARK = [
    ".#######.",
    "#########",
    "......###",
    "###...###",
    "###...###",
    "#########",
    "##.#####.",
]
COIN_LIGHT = {"rim": (255, 89, 83, 255), "ring": (255, 137, 128, 255)}
COIN_DARK = {"rim": (113, 6, 8, 255), "ring": (173, 9, 13, 255)}


def coin():
    """The launcher's red Antagon coin redrawn at 16x16: shaded rim, white mark with a drop shadow."""
    face, shadow, mark = (217, 15, 19, 255), (173, 9, 13, 255), (255, 241, 233, 255)
    image = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            d = ((x - 7.5) ** 2 + (y - 7.5) ** 2) ** 0.5
            if d > 7.4:
                continue
            tone = COIN_LIGHT if x + y < 15 else COIN_DARK
            image.putpixel((x, y), tone["rim"] if d > 6.5 else tone["ring"] if d > 5.6 else face)
    ox, oy = (16 - len(COIN_MARK[0])) // 2, (16 - len(COIN_MARK)) // 2
    for y, row in enumerate(COIN_MARK):
        for x, key in enumerate(row):
            if key == "#":
                image.putpixel((x + ox, y + oy), mark)
    for y, row in enumerate(COIN_MARK):
        for x, key in enumerate(row):
            last = y + 1 == len(COIN_MARK) or COIN_MARK[y + 1][x] != "#"
            if key == "#" and last and image.getpixel((x + ox, y + oy + 1)) == face:
                image.putpixel((x + ox, y + oy + 1), shadow)
    return image


def main():
    logo().save(OUT / "logo.png")
    tab_logo().save(OUT / "tab-logo.png")
    tab_logo("y", "Y").save(OUT / "tab-logo-admin.png")
    draw(CHAT).save(OUT / "chat.png")
    coin().save(OUT / "store.png")
    draw(CROWN).save(OUT / "admin.png")


if __name__ == "__main__":
    main()
