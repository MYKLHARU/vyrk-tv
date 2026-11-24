#!/usr/bin/env python3
"""
Generates the textures and the sound for VYRK's opening (the forged V; see
core/brand/IntroTimeline.kt for the sequence and ui/intro/ForgedScene.kt for how it is drawn):

  res/drawable-nodpi/intro_iron.webp    the dark iron surface, lit warm from the forge below centre
  res/drawable-nodpi/intro_gold.webp    the V's finished material: brushed gold, bevelled edges
  res/drawable-nodpi/intro_molten.webp  the V's molten material, hottest at the heart of each piece
  res/drawable-nodpi/intro_smoke.webp   one soft wisp of smoke (white; tinted when drawn)
  res/drawable-nodpi/intro_glow.webp    a soft round light (white; tinted when drawn)
  res/drawable-nodpi/intro_bokeh.webp   an out-of-focus ember (white; tinted when drawn)
  res/raw/intro.wav                     the optional sound (off unless Settings › Opening sound)

The V's geometry is read from site/img/mark.svg, the brand's own mark, so the textures line up
with the shapes the app draws (core/brand/Emblem.kt). All but the iron are power-of-two sizes,
for OpenGL ES 2 mipmaps. The textures are fixed (seeded), so every run gives the same files.
Synthesised here; no samples, photos or fonts.

Run from the repository root:  python3 tools/gen_intro.py
Then look at it:               python3 tools/preview_intro.py
"""
import math
import os
import re
import wave

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
NODPI = os.path.join(RES, "drawable-nodpi")


# ------------------------------------------------------------------ the mark
def emblem():
    """The mark's pieces from site/img/mark.svg, as lists of (x, y) in its 0-100 box, with their opacity."""
    svg = open(os.path.join(ROOT, "site", "img", "mark.svg"), encoding="utf-8").read()
    pieces = []
    for attrs, d in re.findall(r'<path ([^>]*?)d="([^"]+)"', svg):
        nums = [float(v) for v in re.findall(r"-?\d+(?:\.\d+)?", d)]
        # M x y H x L x y L x y Z: the H keeps the last y.
        pts, cmds = [], re.findall(r"[MHLZ]", d)
        it = iter(nums)
        y = 0.0
        for c in cmds:
            if c in "ML":
                x, y = next(it), next(it)
                pts.append((x, y))
            elif c == "H":
                pts.append((next(it), y))
        op = re.search(r'fill-opacity="([\d.]+)"', attrs)
        pieces.append((pts, float(op.group(1)) if op else 1.0))
    assert len(pieces) == 3, "site/img/mark.svg should have the two blades and the notch"
    return pieces


# ------------------------------------------------------------------ noise
def value_noise(h, w, cell_y, cell_x, rng):
    """Smooth noise in -1..1 with features about cell_y by cell_x pixels."""
    gh, gw = max(2, int(h / cell_y) + 3), max(2, int(w / cell_x) + 3)
    grid = rng.standard_normal((gh, gw)).astype(np.float32)
    img = Image.fromarray(grid, mode="F").resize((int(gw * cell_x), int(gh * cell_y)), Image.BICUBIC)
    a = np.asarray(img)[:h, :w]
    return a / (np.abs(a).max() + 1e-6)


def fbm(h, w, base, octaves, rng, gain=0.55, aspect=1.0):
    out = np.zeros((h, w), np.float32)
    amp, total, cell = 1.0, 0.0, float(base)
    for _ in range(octaves):
        out += amp * value_noise(h, w, cell, cell * aspect, rng)
        total += amp
        amp *= gain
        cell = max(1.5, cell / 2)
    return out / total


def blur(a, sigma):
    """Gaussian blur of a 2D float array."""
    r = int(3 * sigma) + 1
    k = np.exp(-0.5 * (np.arange(-r, r + 1) / sigma) ** 2)
    k /= k.sum()
    a = np.apply_along_axis(lambda v: np.convolve(np.pad(v, r, mode="edge"), k, "valid"), 1, a)
    return np.apply_along_axis(lambda v: np.convolve(np.pad(v, r, mode="edge"), k, "valid"), 0, a)


def to_srgb(lin):
    lin = np.clip(lin, 0, 1)
    return np.where(lin <= 0.0031308, lin * 12.92, 1.055 * np.power(lin, 1 / 2.4) - 0.055)


def to_linear(srgb):
    srgb = np.asarray(srgb, np.float32)
    return np.where(srgb <= 0.04045, srgb / 12.92, np.power((srgb + 0.055) / 1.055, 2.4))


def hex_lin(h):
    return to_linear(np.array([int(h[i:i + 2], 16) / 255 for i in (1, 3, 5)], np.float32))


def save(name, rgb, alpha=None, quality=86, lossless=False, rng=None):
    """Write sRGB floats (0..1) as WebP, dithered so dark gradients don't band."""
    rng = rng or np.random.default_rng(1)
    dither = (rng.random(rgb.shape[:2]) - 0.5)[..., None] / 255.0
    rgb8 = np.clip(np.round((rgb + dither) * 255), 0, 255).astype(np.uint8)
    if alpha is None:
        img = Image.fromarray(rgb8, "RGB")
    else:
        a8 = np.clip(np.round((alpha + dither[..., 0]) * 255), 0, 255).astype(np.uint8)
        img = Image.fromarray(np.dstack([rgb8, a8]), "RGBA")
    os.makedirs(NODPI, exist_ok=True)
    path = os.path.join(NODPI, name)
    img.save(path, "WEBP", quality=quality, lossless=lossless, method=6, exact=alpha is not None)
    print(f"{os.path.relpath(path, ROOT)}  {img.size[0]}x{img.size[1]}  {os.path.getsize(path) // 1024} KB")


# ------------------------------------------------------------------ iron
def iron(w=1920, h=1080):
    """Dark forged iron: hammer dents, mottled patina, a little brushing, lit warm and low from
    below the centre so the light rakes across the dents. Full HD, so a TV shows it 1:1. No
    vignette: the app darkens the edges itself, to the shape of the screen."""
    rng = np.random.default_rng(11)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    height = 1.3 * fbm(h, w, 255, 6, rng)
    height += 0.08 * value_noise(h, w, 3.0, 105, rng)         # a trace of brushing
    for _ in range(420):                                      # hammer blows: soft, uneven dents
        cx, cy = rng.uniform(-60, w + 60), rng.uniform(-60, h + 60)
        r = rng.uniform(16, 58)
        a_, c, sn = rng.uniform(0.6, 1.0), math.cos(rng.uniform(0, math.pi)), 0.0
        sn = math.sqrt(1 - c * c)
        x0, x1 = int(max(0, cx - 2 * r)), int(min(w, cx + 2 * r + 1))
        y0, y1 = int(max(0, cy - 2 * r)), int(min(h, cy + 2 * r + 1))
        if x0 >= x1 or y0 >= y1:
            continue
        dx, dy = xx[y0:y1, x0:x1] - cx, yy[y0:y1, x0:x1] - cy
        u, v = (dx * c + dy * sn) / r, (-dx * sn + dy * c) / (r * a_)
        height[y0:y1, x0:x1] -= rng.uniform(0.35, 0.9) * np.exp(-1.6 * (u * u + v * v))
    for _ in range(50):                                       # pits
        cx, cy, r = rng.uniform(0, w), rng.uniform(0, h), rng.uniform(1.5, 4)
        x0, x1, y0, y1 = int(max(0, cx - 4 * r)), int(min(w, cx + 4 * r)), int(max(0, cy - 4 * r)), int(min(h, cy + 4 * r))
        d2 = ((xx[y0:y1, x0:x1] - cx) ** 2 + (yy[y0:y1, x0:x1] - cy) ** 2) / (r * r)
        height[y0:y1, x0:x1] -= 0.8 * np.exp(-d2)
    height += 0.10 * rng.standard_normal((h, w)).astype(np.float32)   # grain
    height = blur(height, 0.8)
    gy, gx = np.gradient(height)
    n = np.dstack([-gx * 1.6, -gy * 1.6, np.ones_like(gx)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)

    lx, ly, lz = 0.5 * w, 0.66 * h, 260.0
    lv = np.dstack([lx - xx, ly - yy, np.full_like(xx, lz)])
    dist = np.linalg.norm(lv[..., :2], axis=2)
    lv /= np.linalg.norm(lv, axis=2, keepdims=True)
    fall = 1.0 / (1.0 + (dist / 460.0) ** 2) ** 1.6
    diffuse = np.clip((n * lv).sum(2), 0, 1) * fall
    hv = lv + np.array([0, 0, 1], np.float32)
    hv /= np.linalg.norm(hv, axis=2, keepdims=True)
    spec = np.clip((n * hv).sum(2), 0, 1) ** 16 * fall

    patina = np.clip(0.45 + 1.1 * fbm(h, w, 360, 4, rng), 0, 1)[..., None]
    iron_col = np.array([0.030, 0.033, 0.040], np.float32)
    rust_col = np.array([0.048, 0.026, 0.014], np.float32)
    albedo = iron_col * (1 - 0.5 * patina) + rust_col * 0.5 * patina
    albedo *= (1 + 0.35 * fbm(h, w, 54, 3, rng))[..., None]
    warm = np.array([1.0, 0.52, 0.22], np.float32)
    lin = albedo * (0.03 + 1.5 * diffuse[..., None] * warm) + 0.22 * spec[..., None] * warm

    srgb = to_srgb(lin)
    print(f"  iron sRGB mean {srgb.mean() * 255:.0f}, centre {srgb[int(0.62 * h), w // 2].mean() * 255:.0f}, "
          f"max {srgb.max() * 255:.0f}, corner {srgb[8, 8].mean() * 255:.0f}")
    save("intro_iron.webp", srgb, quality=80, rng=rng)


# ------------------------------------------------------------------ the V's materials
def piece_fields(size, pieces):
    """Per pixel of a size x size image of the 0-100 box: the nearest piece's index, the signed
    distance into it (box units, positive inside) and the direction to its nearest edge."""
    s = 100.0 / size
    yy, xx = (np.mgrid[0:size, 0:size].astype(np.float32) + 0.5) * s
    best = np.full((size, size), -1e9, np.float32)
    index = np.zeros((size, size), np.int32)
    out_x = np.zeros((size, size), np.float32)
    out_y = np.zeros((size, size), np.float32)
    for k, (pts, _) in enumerate(pieces):
        inside = np.zeros((size, size), bool)
        dmin = np.full((size, size), 1e9, np.float32)
        nx = np.zeros((size, size), np.float32)
        ny = np.zeros((size, size), np.float32)
        for i in range(len(pts)):
            (ax, ay), (bx, by) = pts[i], pts[(i + 1) % len(pts)]
            ex, ey = bx - ax, by - ay
            tt = np.clip(((xx - ax) * ex + (yy - ay) * ey) / (ex * ex + ey * ey), 0, 1)
            px, py = ax + tt * ex - xx, ay + tt * ey - yy
            d = np.sqrt(px * px + py * py)
            closer = d < dmin
            dmin = np.where(closer, d, dmin)
            nx = np.where(closer, px, nx)
            ny = np.where(closer, py, ny)
            cross = ((ay > yy) != (by > yy)) & (xx < (bx - ax) * (yy - ay) / (by - ay + 1e-9) + ax)
            inside ^= cross
        sd = np.where(inside, dmin, -dmin)
        take = sd > best
        best = np.where(take, sd, best)
        index = np.where(take, k, index)
        norm = np.sqrt(nx * nx + ny * ny) + 1e-6
        out_x = np.where(take, nx / norm, out_x)
        out_y = np.where(take, ny / norm, out_y)
    return xx, yy, best, index, out_x, out_y


def axis(pts):
    """A piece's long direction (unit vector in the box), for the brushing."""
    best = None
    for i in range(len(pts)):
        for j in range(i + 1, len(pts)):
            dx, dy = pts[j][0] - pts[i][0], pts[j][1] - pts[i][1]
            if best is None or dx * dx + dy * dy > best[0]:
                best = (dx * dx + dy * dy, dx, dy)
    l = math.sqrt(best[0])
    return best[1] / l, best[2] / l


def brushed(size, angle, rng):
    """Fine streaks along [angle] (radians), -1..1."""
    big = int(size * 1.5)
    streaks = 0.7 * value_noise(big, big, 1.2, 140, rng) + 0.3 * value_noise(big, big, 3.0, 40, rng)
    img = Image.fromarray(streaks.astype(np.float32), mode="F").rotate(-math.degrees(angle), Image.BICUBIC)
    o = (big - size) // 2
    return np.asarray(img)[o:o + size, o:o + size]


def gold(size=1024):
    """Brushed gold: dark where it reflects the room, a bright band across each blade where the
    brushing catches the light, crisp lit and shadowed bevels. The right blade is paler, as in the mark."""
    pieces = emblem()
    rng = np.random.default_rng(23)
    xx, yy, sd, index, ox, oy = piece_fields(size, pieces)
    tints = [hex_lin("#D4A65A"), hex_lin("#F3DEB0"), hex_lin("#D4A65A")]
    bands = [(0.30, 0.20), (0.38, 0.24), (0.45, 0.35)]       # where the bright band crosses each piece
    rgb = np.zeros((size, size, 3), np.float32)
    alpha = np.zeros((size, size), np.float32)
    bevel = 1.3
    for k, (pts, opacity) in enumerate(pieces):
        m = index == k
        ax_, ay_ = axis(pts)
        streak = brushed(size, math.atan2(ay_, ax_), rng)
        # Position along the piece's length, 0 at its top and 1 at its far end.
        top = min(pts, key=lambda p: p[1])
        u = ((xx - top[0]) * ax_ + (yy - top[1]) * ay_) / max(((p[0] - top[0]) * ax_ + (p[1] - top[1]) * ay_) for p in pts)
        tint = tints[k]
        u0, width = bands[k]
        band = np.exp(-((u - u0) / width) ** 2) * (0.75 + 0.45 * streak)
        band2 = 0.35 * np.exp(-((u - u0 - 0.42) / (width * 0.6)) ** 2) * (0.7 + 0.5 * streak)
        room = np.clip(0.42 - 0.38 * u + 0.08 * streak, 0.04, 1)
        level = 0.07 + 0.55 * room + 1.05 * (band + band2)
        col = tint[None, None, :] * level[..., None]
        # Bevels: edges facing the light (top left) flash, the others drop into bronze.
        edge = np.clip(1 - sd / bevel, 0, 1) ** 1.3
        facing = (ox * -0.55 + oy * -0.84)            # +1 for edges whose outside faces up-left
        lit = np.clip(facing, 0, 1) * edge
        shade = np.clip(-facing, 0, 1) * edge
        col = col * (1 - 0.6 * shade)[..., None] + (tint * 1.15 + 0.10) * (1.25 * lit)[..., None]
        # The forge's warmth from below, in the lower half.
        col += np.array([0.10, 0.025, 0.0], np.float32) * np.clip(u - 0.5, 0, 1)[..., None]
        col *= (1 + 0.08 * streak)[..., None]
        rgb[m] = col[m]
        alpha[m] = opacity
    save("intro_gold.webp", to_srgb(rgb), alpha, quality=92, rng=rng)


def molten(size=512):
    pieces = emblem()
    rng = np.random.default_rng(31)
    xx, yy, sd, index, _, _ = piece_fields(size, pieces)
    flow = fbm(size, size, size / 7, 5, rng)
    heat = np.clip(sd / 4.2, 0, 1) ** 0.7 * (0.80 + 0.38 * flow)
    heat = np.clip(heat, 0, 1)
    stops = [(0.0, "#6A1203"), (0.22, "#C8380A"), (0.48, "#FF7C18"), (0.72, "#FFB840"), (0.9, "#FFE59A"), (1.0, "#FFF8E6")]
    rgb = np.zeros((size, size, 3), np.float32)
    for (a, ca), (b, cb) in zip(stops, stops[1:]):
        ca, cb = hex_lin(ca), hex_lin(cb)
        f = np.clip((heat - a) / (b - a), 0, 1)[..., None]
        sel = ((heat >= a) & (heat <= b))[..., None]
        rgb = np.where(sel, ca * (1 - f) + cb * f, rgb)
    alpha = np.array([pieces[k][1] for k in range(3)], np.float32)[index] ** 0.5
    save("intro_molten.webp", to_srgb(rgb), alpha, quality=88, rng=rng)


# ------------------------------------------------------------------ smoke, glow, bokeh
def smoke(w=512, h=256):
    rng = np.random.default_rng(41)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ex, ey = (xx - w / 2) / (w * 0.46), (yy - h / 2) / (h * 0.42)
    shape = np.exp(-2.6 * (ex * ex + ey * ey))
    billow = 0.5 + 0.5 * fbm(h, w, 90, 5, rng)
    a = np.clip((billow - 0.38) * 2.2, 0, 1) ** 1.3 * shape
    save("intro_smoke.webp", np.ones((h, w, 3), np.float32), a / a.max(), quality=80, rng=rng)


def glow(size=256):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    r = np.sqrt((xx - size / 2 + 0.5) ** 2 + (yy - size / 2 + 0.5) ** 2) / (size / 2)
    a = np.clip(1 - r, 0, 1) ** 2.4 * (0.7 + 0.3 * np.exp(-12 * r * r))
    save("intro_glow.webp", np.ones((size, size, 3), np.float32), a / a.max(), lossless=True)


def bokeh(size=128):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    r = np.sqrt((xx - size / 2 + 0.5) ** 2 + (yy - size / 2 + 0.5) ** 2) / (size / 2 - 3)
    disc = np.clip((1 - r) * 9, 0, 1)
    a = disc * (0.55 + 0.45 * np.clip((r - 0.55) / 0.4, 0, 1) ** 2)
    save("intro_bokeh.webp", np.ones((size, size, 3), np.float32), a / a.max(), lossless=True)


# ------------------------------------------------------------------ sound
def sound(path, sr=22050, dur=3.6):
    """Quiet and low: the forge's breath, a hiss as the gold runs, one bright metal note on the
    pulse with a soft low thump, a shimmer under the wordmark. Peaks at about -9 dBFS."""
    rng = np.random.default_rng(53)
    n = int(sr * dur)
    t = np.arange(n) / sr

    def env(a, b, c, d):   # 0 before a, up to 1 by b, held to c, down to 0 by d
        return np.clip(np.minimum((t - a) / max(b - a, 1e-3), (d - t) / max(d - c, 1e-3)), 0, 1)

    def onepole(x, cutoff):
        k = math.exp(-2 * math.pi * cutoff / sr)
        y = np.empty_like(x)
        acc = 0.0
        for i, v in enumerate(x):
            acc = (1 - k) * v + k * acc
            y[i] = acc
        return y

    noise = rng.standard_normal(n).astype(np.float32)
    # The forge: filtered rumble and a low drone that swells and settles.
    rumble = onepole(onepole(noise, 180), 180) * 3.2 * env(0.0, 0.7, 2.9, 3.55)
    drone = (np.sin(2 * np.pi * 55 * t) + 0.45 * np.sin(2 * np.pi * 110 * t + 0.4) + 0.2 * np.sin(2 * np.pi * 165 * t)) \
        * 0.16 * env(0.0, 0.9, 2.8, 3.55)
    # Crackle: a few soft clicks.
    crackle = np.zeros(n, np.float32)
    for at in rng.uniform(0.05, 2.8, 26):
        i = int(at * sr)
        k = np.arange(min(160, n - i))
        crackle[i:i + len(k)] += rng.uniform(0.15, 0.4) * np.exp(-k / 18.0) * rng.standard_normal(len(k))
    crackle = crackle - onepole(crackle, 900)
    # The run of molten gold: a high hiss that follows the trace.
    hiss = (noise - onepole(noise, 2600)) * env(0.6, 0.75, 1.25, 1.6) * 0.22
    # The pulse at 1.8 s: an inharmonic bell note and a soft thump.
    p = np.clip(t - 1.8, 0, None)
    on = (t >= 1.8).astype(np.float32)
    bell = sum(a * np.sin(2 * np.pi * f * p) * np.exp(-p / d) for f, a, d in
               ((392.0, 0.30, 1.1), (987.8, 0.16, 0.7), (1567.9, 0.10, 0.45), (2637.0, 0.05, 0.3))) * on
    thump = 0.55 * np.sin(2 * np.pi * (58 + 30 * np.exp(-p / 0.05)) * p) * np.exp(-p / 0.16) * on
    # A shimmer under the wordmark.
    s = np.clip(t - 2.1, 0, None)
    shimmer = sum(np.sin(2 * np.pi * f * s + ph) for f, ph in ((1318.5, 0), (1975.5, 1.1), (2637.0, 2.3))) \
        * 0.025 * env(2.1, 2.5, 2.9, 3.45)
    mix = rumble + drone + 0.5 * crackle + hiss + bell + thump + shimmer
    mix *= env(0.0, 0.05, 3.3, 3.58)
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    mix *= 0.35 / (np.abs(mix).max() + 1e-6)
    data = (np.clip(mix, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(data.tobytes())
    print(f"{os.path.relpath(path, ROOT)}  {dur:.1f} s  {os.path.getsize(path) // 1024} KB")


if __name__ == "__main__":
    iron()
    gold()
    molten()
    smoke()
    glow()
    bokeh()
    sound(os.path.join(RES, "raw", "intro.wav"))
