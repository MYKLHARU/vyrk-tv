#!/usr/bin/env python3
"""Build README images from showcase captures of the real app.

Usage: python docs/tools/make_images.py <captures-dir> <repo-root>

Writes:
  docs/images/screenshots/<name>.jpg   1600x900 each
  docs/images/banner.png               1600x560, Top 10 capture in a TV frame beside the wordmark
  docs/images/social-preview.png       1280x640, same layout
Never generates app content: every screen comes from a capture.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

OBSIDIAN = (11, 20, 29)
FROST = (169, 225, 243)
TEXT = (244, 246, 250)
MUTED = (180, 186, 196)

NAMES = [
    "home", "top10-rows", "info-page", "series-info", "player", "audio-subtitles", "search",
    "library", "top10-suggestion", "addons", "settings", "first-run",
]


def font(root: Path, size: int, weight: int = 800) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(str(root / "app/src/main/res/font/plus_jakarta_sans.ttf"), size)
    try:
        f.set_variation_by_axes([weight])
    except (OSError, AttributeError):
        pass
    return f


def draw_mark(d: ImageDraw.ImageDraw, x: int, y: int, s: float):
    """The angular V, same geometry as res/drawable/ic_vyrk_mark.xml (100x100 viewport)."""
    def p(px, py):
        return (x + px * s, y + py * s)
    d.polygon([p(8, 14), p(30, 14), p(54, 70), p(50, 88)], fill=FROST)
    d.polygon([p(92, 14), p(70, 14), p(56, 47), p(63, 63)], fill=(230, 247, 252))
    d.polygon([p(40, 14), p(60, 14), p(50, 36)], fill=(120, 160, 175))


def tv_frame(capture: Image.Image, width: int) -> Image.Image:
    """Capture inside a thin-bezel TV with a stand and soft shadow."""
    h = int(width * 9 / 16)
    bezel = max(8, width // 90)
    tv = Image.new("RGBA", (width + bezel * 2, h + bezel * 2 + width // 14), (0, 0, 0, 0))
    d = ImageDraw.Draw(tv)
    d.rounded_rectangle([0, 0, width + bezel * 2, h + bezel * 2], radius=bezel * 2, fill=(18, 22, 28, 255), outline=(60, 70, 80, 255), width=2)
    tv.paste(capture.resize((width, h), Image.LANCZOS), (bezel, bezel))
    cx = tv.width // 2
    d.polygon([(cx - width // 12, h + bezel * 2), (cx + width // 12, h + bezel * 2), (cx + width // 7, tv.height - 4), (cx - width // 7, tv.height - 4)], fill=(30, 36, 44, 255))
    return tv


def hero_image(root: Path, capture: Image.Image, size, out: Path):
    w, h = size
    img = Image.new("RGB", size, OBSIDIAN)
    glow = Image.new("RGBA", size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([int(w * 0.35), -h // 2, int(w * 1.3), int(h * 1.3)], fill=(169, 225, 243, 40))
    img.paste(glow.filter(ImageFilter.GaussianBlur(h // 4)), (0, 0), glow.filter(ImageFilter.GaussianBlur(h // 4)))
    tv = tv_frame(capture, int(w * 0.52))
    shadow = Image.new("RGBA", (tv.width + 60, tv.height + 60), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([30, 30, tv.width + 30, tv.height - tv.height // 9 + 30], radius=20, fill=(0, 0, 0, 160))
    shadow = shadow.filter(ImageFilter.GaussianBlur(24))
    tx = w - tv.width - int(w * 0.04)
    ty = (h - tv.height) // 2 + h // 40
    img.paste(shadow, (tx - 30, ty - 10), shadow)
    img.paste(tv, (tx, ty), tv)
    d = ImageDraw.Draw(img)
    mark = int(h * 0.20)
    left = int(w * 0.06)
    top = int(h * 0.28)
    draw_mark(d, left, top, mark / 100)
    d.text((left + mark * 1.05, top + mark * 0.02), "vyrk", font=font(root, int(mark * 0.92), 800), fill=TEXT)
    d.text((left, top + mark * 1.25), "Forged for Streaming", font=font(root, int(h * 0.065), 700), fill=FROST)
    d.text((left, top + mark * 1.25 + h * 0.09), "Browse, choose, press Play.\nEverything with the remote.", font=font(root, int(h * 0.04), 500), fill=MUTED, spacing=8)
    img.save(out)


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    caps = Path(sys.argv[1])
    root = Path(sys.argv[2])
    shots = root / "docs/images/screenshots"
    shots.mkdir(parents=True, exist_ok=True)
    missing = []
    for name in NAMES:
        src = caps / f"{name}.png"
        if not src.exists():
            missing.append(name)
            continue
        Image.open(src).convert("RGB").resize((1600, 900), Image.LANCZOS).save(shots / f"{name}.jpg", quality=88, optimize=True, progressive=True)
    top10 = caps / "top10-rows.png"
    if top10.exists():
        cap = Image.open(top10).convert("RGB")
        hero_image(root, cap, (1600, 560), root / "docs/images/banner.png")
        hero_image(root, cap, (1280, 640), root / "docs/images/social-preview.png")
    if missing:
        print("missing captures: " + ", ".join(missing), file=sys.stderr)
        return 1
    print(f"wrote {len(NAMES)} screenshots, banner and social preview")
    return 0


if __name__ == "__main__":
    sys.exit(main())
