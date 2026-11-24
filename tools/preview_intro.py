#!/usr/bin/env python3
"""
Renders VYRK's opening to images (and a video if ffmpeg is installed), to look at it and tune
it without a device. It mirrors app/src/main/java/com/vyrk/tv/ui/intro/ForgedScene.kt and reads
the timing straight from core/src/main/kotlin/com/vyrk/core/brand/IntroTimeline.kt, so a change
to the timing shows here as it will in the app. Fonts are stand-ins (the app uses the system
serif for VYRK); materials and motion match.

  python3 tools/preview_intro.py                     frames at 0.3, 0.9, 1.4, 1.8, 2.3, 2.9 and 3.3 s
  python3 tools/preview_intro.py --times 1.2 1.6     chosen moments
  python3 tools/preview_intro.py --video             the whole thing at 30 fps (needs ffmpeg)
  python3 tools/preview_intro.py --size 1080x2400    a phone held upright

Output goes to build/intro-preview/.
"""
import argparse
import math
import os
import re
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NODPI = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
TIMELINE = os.path.join(ROOT, "core", "src", "main", "kotlin", "com", "vyrk", "core", "brand", "IntroTimeline.kt")
OUT = os.path.join(ROOT, "build", "intro-preview")

# ------------------------------------------------------------------ timing (from IntroTimeline.kt)
T = {k: float(v) for k, v in re.findall(r"const val (\w+) = ([\d.]+)f", open(TIMELINE, encoding="utf-8").read())}


def progress(t, a, b): return min(max((t - a) / (b - a), 0.0), 1.0)
def ease_out_cubic(x): return 1 - (1 - x) ** 3
def ease_in_out_cubic(x): return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2
def ease_in_out_sine(x): return -(math.cos(math.pi * x) - 1) / 2
def smoothstep(x): x = min(max(x, 0.0), 1.0); return x * x * (3 - 2 * x)
def reveal(t): return 0.75 * ease_out_cubic(progress(t, 0, T["REVEAL_END"])) + 0.25 * smoothstep(progress(t, T["REVEAL_END"], 1.2))
def trace_blades(t): return ease_in_out_sine(progress(t, T["TRACE_START"], T["TRACE_END"]))
def trace_notch(t): return ease_in_out_sine(progress(t, T["NOTCH_TRACE_START"], T["TRACE_END"]))
def pour_front(t): return 10 + 82 * ease_in_out_sine(progress(t, T["POUR_START"], T["POUR_END"]))
def cool_front(t): return 6 + 94 * ease_in_out_sine(progress(t, T["COOL_START"], T["COOL_END"]))
def settle(t): return ease_in_out_cubic(progress(t, T["SETTLE_START"], T["SETTLE_END"]))
def push(t): return ease_in_out_sine(progress(t, T["PUSH_START"], T["END"]))


def pulse(t):
    a, r, d = T["PULSE_AT"], T["PULSE_RISE"], T["PULSE_DECAY"]
    if t < a - r: return 0.0
    if t < a: return smoothstep(progress(t, a - r, a))
    v = math.exp(-(t - a) / (d / 3))
    return 0.0 if v < 0.01 else v


def letter(t, i):
    s = T["WORDMARK_START"] + i * T["WORDMARK_LETTER_STAGGER"]
    return ease_out_cubic(progress(t, s, s + T["WORDMARK_LETTER_FADE"]))


def wordmark_tracking(t): return ease_out_cubic(progress(t, T["WORDMARK_START"], T["WORDMARK_START"] + 0.8))


def wordmark_highlight(t):
    if t < T["WORDMARK_HIGHLIGHT_START"]: return -1.0
    if t > T["WORDMARK_HIGHLIGHT_END"]: return 2.0
    return ease_in_out_sine(progress(t, T["WORDMARK_HIGHLIGHT_START"], T["WORDMARK_HIGHLIGHT_END"]))


def tagline(t): return ease_out_cubic(progress(t, T["TAGLINE_START"], T["TAGLINE_START"] + T["TAGLINE_FADE"]))
def visibility(t): return 1 - ease_in_out_sine(progress(t, T["DISSOLVE_START"], T["END"]))


# ------------------------------------------------------------------ the scene (mirror of ForgedScene.kt)
LEFT = [(8, 14), (30, 14), (54, 70), (50, 88)]
RIGHT = [(92, 14), (70, 14), (56, 47), (63, 63)]
NOTCH = [(40, 14), (60, 14), (50, 36)]
PIECES = [LEFT, RIGHT, NOTCH]
BASE = (5, 4, 4)
FORM_SCALE = 1.10


def trace_order(p): return [p[0]] + list(reversed(p[1:]))


def hexc(h): return tuple(int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))


# Embers: x, y (fractions of the screen), rise (screen heights per second), sway (px at 1080p),
# sway rate (Hz), phase, size (px at 1080p), flicker rate (Hz), kind (spark/bokeh), layer (back/front),
# start time, colour.
EMBERS = [
    (0.31, 0.86, 0.060, 14, 0.35, 0.0, 2.6, 5.1, "spark", "back", -1.0, "#FFB04A"),
    (0.44, 0.97, 0.075, 10, 0.50, 1.3, 2.0, 6.3, "spark", "back", -0.6, "#FF9A36"),
    (0.58, 0.90, 0.052, 18, 0.28, 2.1, 3.0, 4.2, "spark", "back", -1.4, "#FFC266"),
    (0.69, 1.02, 0.080, 12, 0.42, 0.7, 2.2, 7.0, "spark", "back", -0.2, "#FF8A2E"),
    (0.23, 1.05, 0.068, 16, 0.31, 2.6, 1.8, 5.6, "spark", "back", 0.2, "#FFA040"),
    (0.77, 0.80, 0.045, 9, 0.55, 1.9, 1.6, 6.8, "spark", "back", -1.8, "#FFB85A"),
    (0.50, 1.04, 0.090, 11, 0.47, 0.4, 2.4, 5.9, "spark", "back", 0.6, "#FFD27A"),
    (0.37, 1.06, 0.070, 13, 0.38, 1.7, 1.7, 6.6, "spark", "back", 1.3, "#FF9A36"),
    (0.63, 1.06, 0.082, 15, 0.33, 2.9, 2.1, 4.8, "spark", "back", 1.9, "#FFB04A"),
    (0.16, 0.74, 0.030, 20, 0.22, 0.9, 22.0, 2.1, "bokeh", "back", -2.0, "#FF8A2E"),
    (0.86, 0.66, 0.026, 22, 0.20, 2.4, 28.0, 1.7, "bokeh", "back", -2.0, "#FF9A40"),
    (0.72, 0.98, 0.040, 18, 0.25, 1.1, 18.0, 2.4, "bokeh", "back", 0.4, "#FFB050"),
    (0.09, 0.92, 0.050, 30, 0.18, 0.3, 70.0, 1.3, "bokeh", "front", -1.0, "#FF7A24"),
    (0.93, 1.10, 0.058, 26, 0.16, 1.6, 90.0, 1.1, "bokeh", "front", 0.5, "#FF8A2E"),
    (0.26, 1.08, 0.110, 8, 0.6, 0.2, 2.6, 8.0, "spark", "front", 1.1, "#FFC266"),
]
# Smoke: centre x, y (fractions), width (fraction of the larger side), drift x, y (fractions per
# second), rotation (degrees per second), opacity, colour, layer.
SMOKE = [
    (0.30, 0.70, 0.95, 0.006, -0.018, 1.5, 0.10, "#8A7C70", "back"),
    (0.72, 0.62, 0.85, -0.008, -0.014, -1.2, 0.08, "#857668", "back"),
    (0.50, 0.58, 0.70, 0.003, -0.020, 0.8, 0.09, "#B07A52", "back"),
    (0.55, 0.96, 1.10, -0.010, -0.024, -0.6, 0.05, "#7A6C62", "front"),
]


class Frame:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = np.zeros((h, w, 3), np.float32)
        self.px[:] = np.array(BASE, np.float32) / 255

    def blend(self, x0, y0, rgb, a, mode="over"):
        """Composite an rgb (h,w,3) with coverage a (h,w) at x0, y0 (clipped)."""
        h, w = a.shape
        x1, y1 = x0 + w, y0 + h
        cx0, cy0, cx1, cy1 = max(0, x0), max(0, y0), min(self.w, x1), min(self.h, y1)
        if cx0 >= cx1 or cy0 >= cy1:
            return
        sa = a[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0][..., None]
        sc = rgb[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0] if rgb.ndim == 3 else np.broadcast_to(rgb, (cy1 - cy0, cx1 - cx0, 3))
        dst = self.px[cy0:cy1, cx0:cx1]
        if mode == "add":
            self.px[cy0:cy1, cx0:cx1] = np.minimum(dst + sc * sa, 1)
        else:
            self.px[cy0:cy1, cx0:cx1] = dst * (1 - sa) + sc * sa


def load(name):
    return np.asarray(Image.open(os.path.join(NODPI, name)).convert("RGBA")).astype(np.float32) / 255


ASSETS = {}


def asset(name):
    if name not in ASSETS:
        ASSETS[name] = load(name)
    return ASSETS[name]


def sprite(frame, name, cx, cy, sw, sh, alpha, tint=(1, 1, 1), mode="add", rot=0.0):
    if alpha <= 0.002 or sw < 1 or sh < 1:
        return
    img = Image.fromarray((asset(name) * 255).astype(np.uint8), "RGBA")
    img = img.resize((max(1, int(sw)), max(1, int(sh))), Image.BILINEAR)
    if rot:
        img = img.rotate(rot, Image.BILINEAR, expand=True)
    a = np.asarray(img).astype(np.float32) / 255
    rgb = a[..., :3] * np.array(tint, np.float32)
    frame.blend(int(cx - a.shape[1] / 2), int(cy - a.shape[0] / 2), rgb, a[..., 3] * alpha, mode)


def layout(w, h):
    """Emblem box size and where things sit in the final lockup (matches ForgedScene.layout)."""
    e = min(0.41 * h, 0.60 * w)
    word = 0.255 * e
    tag = 0.090 * e
    cap_w, cap_t = 0.70 * word, 0.70 * tag
    gap1, gap2 = 0.15 * e, 0.11 * e
    total = 0.74 * e + gap1 + cap_w + gap2 + cap_t
    top = h * 0.5 - total / 2 - 0.012 * h
    box_y = top - 0.14 * e
    word_base = top + 0.74 * e + gap1 + cap_w
    tag_base = word_base + gap2 + cap_t
    return e, box_y, word, word_base, tag, tag_base


def emblem_transform(t, w, h):
    """Box → screen: scale per box unit and the box's top-left, before the camera push."""
    e, box_y, *_ = layout(w, h)
    final_s, final_x, final_y = e / 100, w / 2 - e / 2, box_y
    form_s = final_s * FORM_SCALE
    form_x, form_y = w / 2 - 50 * form_s, h / 2 - 51 * form_s
    k = settle(t)
    s = form_s + (final_s - form_s) * k
    return s, form_x + (final_x - form_x) * k, form_y + (final_y - form_y) * k


def pushed(x, y, w, h, amount):
    return w / 2 + (x - w / 2) * amount, h / 2 + (y - h / 2) * amount


def polygon_mask(frame, pts_screen, ss=4):
    xs = [p[0] for p in pts_screen]
    ys = [p[1] for p in pts_screen]
    x0, y0 = int(math.floor(min(xs))) - 2, int(math.floor(min(ys))) - 2
    x1, y1 = int(math.ceil(max(xs))) + 2, int(math.ceil(max(ys))) + 2
    im = Image.new("L", ((x1 - x0) * ss, (y1 - y0) * ss), 0)
    ImageDraw.Draw(im).polygon([((x - x0) * ss, (y - y0) * ss) for x, y in pts_screen], fill=255)
    m = np.asarray(im.resize((x1 - x0, y1 - y0), Image.BOX)).astype(np.float32) / 255
    return x0, y0, m


def lines_mask(segs, x0, y0, wpx, hpx, width, ss=3):
    im = Image.new("L", (wpx * ss, hpx * ss), 0)
    d = ImageDraw.Draw(im)
    for seg in segs:
        if len(seg) >= 2:
            d.line([((x - x0) * ss, (y - y0) * ss) for x, y in seg], fill=255, width=max(1, int(width * ss)), joint="curve")
            r = width * ss / 2
            for x, y in (seg[0], seg[-1]):
                d.ellipse([(x - x0) * ss - r, (y - y0) * ss - r, (x - x0) * ss + r, (y - y0) * ss + r], fill=255)
    return np.asarray(im.resize((wpx, hpx), Image.BOX)).astype(np.float32) / 255


def walk(poly, d0, d1):
    """The part of a closed polyline from distance d0 to d1 along it."""
    pts = poly + [poly[0]]
    out, acc = [], 0.0
    for a, b in zip(pts, pts[1:]):
        l = math.dist(a, b)
        s0, s1 = max(d0, acc), min(d1, acc + l)
        if s1 > s0:
            p0 = (a[0] + (b[0] - a[0]) * (s0 - acc) / l, a[1] + (b[1] - a[1]) * (s0 - acc) / l)
            p1 = (a[0] + (b[0] - a[0]) * (s1 - acc) / l, a[1] + (b[1] - a[1]) * (s1 - acc) / l)
            if not out or math.dist(out[-1], p0) > 1e-6:
                out.append(p0)
            out.append(p1)
        acc += l
    return out


def perimeter(poly): return sum(math.dist(a, b) for a, b in zip(poly, poly[1:] + poly[:1]))


def textured_piece(frame, tex, pts, s, ox, oy, alpha_of_y, mode="over", gain=1.0):
    """Fill a piece with a box-mapped texture, faded by alpha_of_y(box y)."""
    scr = [(ox + x * s, oy + y * s) for x, y in pts]
    x0, y0, m = polygon_mask(frame, scr)
    hh, ww = m.shape
    yy, xx = np.mgrid[0:hh, 0:ww].astype(np.float32)
    bx, by = (xx + x0 + 0.5 - ox) / s, (yy + y0 + 0.5 - oy) / s
    th, tw = tex.shape[:2]
    ix = np.clip((bx / 100 * tw).astype(int), 0, tw - 1)
    iy = np.clip((by / 100 * th).astype(int), 0, th - 1)
    texel = tex[iy, ix]
    a = m * texel[..., 3] * alpha_of_y(by)
    frame.blend(x0, y0, texel[..., :3] * gain, a, mode)
    return x0, y0, m, by


def draw_emblem(frame, t, w, h):
    s0, ox0, oy0 = emblem_transform(t, w, h)
    k = 1 + T["PUSH_LOCKUP"] * push(t)
    ox, oy = pushed(ox0, oy0, w, h, k)
    s = s0 * k
    cx, cy = ox + 50 * s, oy + 51 * s
    gold, molt = asset("intro_gold.webp"), asset("intro_molten.webp")
    cool, pour = cool_front(t), pour_front(t)
    p_pour = progress(t, T["POUR_START"], T["POUR_END"])
    molten_amount = p_pour * (1 - progress(t, T["COOL_START"], T["COOL_END"]))
    gold_amount = progress(t, T["COOL_START"], T["COOL_END"])
    tracing = trace_blades(t)
    # Light the molten metal throws on the iron, and the completing pulse.
    sprite(frame, "intro_glow.webp", cx, cy + 6 * s, 260 * s, 230 * s,
           0.10 * min(1, tracing * 3) + 0.34 * molten_amount + 0.10 * gold_amount, hexc("#FF7A26"))
    pl = pulse(t)
    if pl > 0:
        sprite(frame, "intro_glow.webp", cx, cy, 250 * s, 250 * s, 0.30 * pl, hexc("#FFC878"))
    # Molten fill (under), then gold (over), each faded by its front.
    if p_pour > 0 and cool < 100:
        for pts in PIECES:
            def molten_a(by, cool=cool, pour=pour):
                lo = np.clip((by - (cool - 4.5)) / 9.0, 0, 1)
                hi = np.clip((pour + 1.5 - by) / 3.0, 0, 1)
                return lo * hi
            textured_piece(frame, molt, pts, s, ox, oy, molten_a, gain=1.0)
        # The bright edge of the pour.
        if 0 < p_pour < 1:
            for pts in PIECES:
                textured_piece(frame, molt, pts, s, ox, oy,
                               lambda by, pour=pour: np.clip(1 - np.abs(by - pour) / 2.5, 0, 1) * 0.55, mode="add")
    if gold_amount > 0:
        for pts in PIECES:
            textured_piece(frame, gold, pts, s, ox, oy, lambda by, cool=cool: np.clip((cool + 4.5 - by) / 9.0, 0, 1))
    # The glint that crosses the gold with the pulse.
    g = progress(t, T["PULSE_AT"] - 0.04, T["PULSE_AT"] + 0.36)
    if 0 < g < 1:
        band_c = -20 + 140 * ease_in_out_sine(g)
        for pts in PIECES:
            scr = [(ox + x * s, oy + y * s) for x, y in pts]
            x0, y0, m = polygon_mask(frame, scr)
            hh, ww = m.shape
            yy, xx = np.mgrid[0:hh, 0:ww].astype(np.float32)
            bx, by = (xx + x0 + 0.5 - ox) / s, (yy + y0 + 0.5 - oy) / s
            u = (bx + by) / 1.4142
            band = np.exp(-((u - band_c) / 7.0) ** 2) * 0.55 * math.sin(math.pi * g)
            frame.blend(x0, y0, np.array(hexc("#FFF0C8"), np.float32), m * band, "add")
    # The molten line.
    outline_alpha = 1 - progress(t, T["COOL_START"], T["COOL_END"] + 0.1)
    if tracing > 0 and outline_alpha > 0:
        lw = s
        segs_old, segs_hot, heads = [], [], []
        for pts, p in ((LEFT, trace_blades(t)), (RIGHT, trace_blades(t)), (NOTCH, trace_notch(t))):
            if p <= 0:
                continue
            poly = [(ox + x * s, oy + y * s) for x, y in trace_order(pts)]
            L = perimeter(poly)
            d = p * L
            hot = 0.16 * L
            segs_old.append(walk(poly, 0, max(0.0, d - hot)))
            segs_hot.append(walk(poly, max(0.0, d - hot), d))
            if p < 1:
                heads.append(walk(poly, d - 0.001, d)[-1])
        x0, y0 = int(ox - 6 * s), int(oy - 6 * s)
        wpx, hpx = int(112 * s), int(112 * s)
        for segs, width, col, a in ((segs_old, 2.2 * lw, "#C8461A", 0.16), (segs_old, 0.7 * lw, "#E0662A", 0.55),
                                    (segs_hot, 3.4 * lw, "#FF6A1C", 0.24), (segs_hot, 1.4 * lw, "#FFA040", 0.65),
                                    (segs_hot, 0.5 * lw, "#FFF0D0", 0.95)):
            m = lines_mask(segs, x0, y0, wpx, hpx, width)
            frame.blend(x0, y0, np.array(hexc(col), np.float32), m * a * outline_alpha, "add")
        for hx, hy in heads:
            sprite(frame, "intro_glow.webp", hx, hy, 14 * s, 14 * s, 0.9, hexc("#FFB060"))
            sprite(frame, "intro_glow.webp", hx, hy, 4 * s, 4 * s, 1.0, hexc("#FFF4DC"))


FONT_WORD = None
FONT_TAG = None


def fonts(word_px, tag_px):
    lora = "/usr/share/fonts/truetype/google-fonts/Lora-Variable.ttf"
    word = ImageFont.truetype(lora if os.path.exists(lora) else "DejaVuSerif-Bold.ttf", int(word_px))
    try:
        word.set_variation_by_axes([600])
    except Exception:
        pass
    tag = ImageFont.truetype(os.path.join(ROOT, "app", "src", "main", "res", "font", "plus_jakarta_sans.ttf"), int(tag_px))
    try:
        tag.set_variation_by_axes([500])
    except Exception:
        pass
    return word, tag


def text_layer(chars, font, xs, base, h, w):
    im = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(im)
    for ch, x in zip(chars, xs):
        d.text((x, base), ch, font=font, fill=255, anchor="ls")
    return np.asarray(im).astype(np.float32) / 255


def draw_text(frame, t, w, h):
    e, _, word_px, word_base, tag_px, tag_base = layout(w, h)
    k = 1 + T["PUSH_LOCKUP"] * push(t)
    fw, ft = fonts(word_px * k, tag_px * k)
    # VYRK, letter by letter, the spacing settling from 0.62 em to the wordmark's 0.385 em.
    if t >= T["WORDMARK_START"]:
        track = (0.62 + (0.385 - 0.62) * wordmark_tracking(t)) * word_px * k
        chars = "VYRK"
        adv = [fw.getlength(c) for c in chars]
        total = sum(adv) + track * 3
        cx, base = pushed(w / 2, word_base, w, h, k)
        x = cx - total / 2
        xs = []
        for a in adv:
            xs.append(x)
            x += a + track
        hi = wordmark_highlight(t)
        for i, ch in enumerate(chars):
            a = letter(t, i)
            if a <= 0:
                continue
            rise = (1 - a) * 0.10 * word_px * k
            m = text_layer([ch], fw, [xs[i]], base + rise, h, w)
            yy = np.arange(h, dtype=np.float32)[:, None]
            cap = 0.70 * word_px * k
            f = np.clip((yy - (base - cap)) / cap, 0, 1)
            top, bot = np.array(hexc("#FBF8F1")), np.array(hexc("#B9B0A2"))
            col = (top[None, None, :] * (1 - f[..., None]) + bot[None, None, :] * f[..., None])
            frame.blend(0, 0, np.broadcast_to(col, (h, w, 3)), m * a, "over")
            if -0.2 < hi < 1.2:
                xx = np.arange(w, dtype=np.float32)[None, :]
                bandx = (cx - total / 2 - 0.6 * word_px) + hi * (total + 1.2 * word_px)
                band = np.exp(-(((xx - bandx) + (yy - base) * 0.35) / (0.28 * word_px)) ** 2)
                frame.blend(0, 0, np.array(hexc("#FFF2D6"), np.float32), m * a * band * 0.55, "add")
    # Forged for Streaming.
    a = tagline(t)
    if a > 0:
        track = (0.30 + (0.16 - 0.30) * a) * tag_px * k
        text = "Forged for Streaming"
        adv = [ft.getlength(c) for c in text]
        total = sum(adv) + track * (len(text) - 1)
        cx, base = pushed(w / 2, tag_base, w, h, k)
        base += (1 - a) * 0.25 * tag_px
        x = cx - total / 2
        xs = []
        for av in adv:
            xs.append(x)
            x += av + track
        m = text_layer(list(text), ft, xs, base, h, w)
        frame.blend(0, 0, np.array(hexc("#D4A65A"), np.float32), m * a * 0.92, "over")


def embers(frame, t, w, h, layer):
    unit = min(w, h) / 1080
    k = 1 + (T["PUSH_BACKGROUND"] if layer == "back" else 0.06) * push(t)
    for (x0, y0, rise, sway, sway_hz, ph, size, flick, kind, lay, start, col) in EMBERS:
        if lay != layer:
            continue
        age = t - start
        if age < 0:
            continue
        y = (y0 - rise * age) * h
        x = x0 * w + sway * unit * math.sin(2 * math.pi * sway_hz * t + ph)
        x, y = pushed(x, y, w, h, k)
        life = min(1.0, age / 0.4) * smoothstep((y / h - 0.08) / 0.25)
        fl = 0.72 + 0.28 * math.sin(2 * math.pi * flick * t + ph * 3)
        a = life * fl * (0.35 + 0.65 * reveal(t))
        if kind == "spark":
            sprite(frame, "intro_glow.webp", x, y, size * 9 * unit, size * 9 * unit, 0.42 * a, hexc(col))
            sprite(frame, "intro_glow.webp", x, y, size * 2.2 * unit, size * 2.2 * unit, 1.0 * a, hexc("#FFF0D0"))
        else:
            sprite(frame, "intro_bokeh.webp", x, y, size * unit, size * unit, 0.16 * a, hexc(col))


def smoke(frame, t, w, h, layer):
    big = max(w, h)
    k = 1 + T["PUSH_BACKGROUND"] * push(t)
    for (cx, cy, size, dx, dy, rot, op, col, lay) in SMOKE:
        if lay != layer:
            continue
        x, y = pushed((cx + dx * t) * w, (cy + dy * t) * h, w, h, k)
        sw = size * big * (1 + 0.05 * t)
        sprite(frame, "intro_smoke.webp", x, y, sw, sw * 0.75, 1.6 * op * (0.3 + 0.7 * reveal(t)), hexc(col), mode="over", rot=rot * t)


def vignette(frame, w, h):
    """The edges fall away to black, to the shape of the screen (ForgedScene.vignette)."""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    ex, ey = (xx - 0.5 * w) / (0.5 * w), (yy - 0.53 * h) / min(0.5 * h, 0.7 * w)
    r = np.sqrt(ex * ex + ey * ey)
    # Stops as in the app's radial gradient: clear to 0.45, then to 0.97 black at 1.25.
    a = np.interp(r, [0.0, 0.45, 0.85, 1.25], [0.0, 0.0, 0.62, 0.97]).astype(np.float32)
    frame.blend(0, 0, np.array(BASE, np.float32) / 255, a, "over")


def render(t, w, h):
    f = Frame(w, h)
    # Iron.
    iron = asset("intro_iron.webp")
    kb = 1 + T["PUSH_BACKGROUND"] * push(t)
    ih, iw = iron.shape[:2]
    sc = max(w / iw, h / ih) * kb
    img = Image.fromarray((iron[..., :3] * 255).astype(np.uint8)).resize((int(iw * sc), int(ih * sc)), Image.BILINEAR)
    a = np.asarray(img).astype(np.float32) / 255
    flick = 1 + 0.03 * math.sin(2 * math.pi * 1.7 * t) + 0.02 * math.sin(2 * math.pi * 4.3 * t + 1)
    f.blend(int(w / 2 - a.shape[1] / 2), int(h / 2 - a.shape[0] / 2), a, np.full(a.shape[:2], min(1.0, reveal(t) * flick), np.float32))
    # Haze over the forge.
    sprite(f, "intro_glow.webp", w / 2, h * 0.64, 1.3 * max(w, h), 0.9 * max(w, h), 0.035 * reveal(t) * flick, hexc("#FF7A2A"))
    vignette(f, w, h)
    smoke(f, t, w, h, "back")
    embers(f, t, w, h, "back")
    draw_emblem(f, t, w, h)
    draw_text(f, t, w, h)
    embers(f, t, w, h, "front")
    smoke(f, t, w, h, "front")
    v = visibility(t)
    out = f.px * v + np.array([0.02, 0.04, 0.055], np.float32) * (1 - v)   # the app's dark blue beneath
    return Image.fromarray(np.clip(out * 255, 0, 255).astype(np.uint8))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--times", type=float, nargs="*")
    ap.add_argument("--video", action="store_true")
    ap.add_argument("--size", default="1280x720")
    ap.add_argument("--fps", type=int, default=30)
    args = ap.parse_args()
    w, h = (int(v) for v in args.size.split("x"))
    os.makedirs(OUT, exist_ok=True)
    if args.video:
        frames = os.path.join(OUT, "frames")
        os.makedirs(frames, exist_ok=True)
        n = int(T["END"] * args.fps) + 1
        for i in range(n):
            render(i / args.fps, w, h).save(os.path.join(frames, f"{i:04d}.png"))
        mp4 = os.path.join(OUT, f"intro-{w}x{h}.mp4")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(args.fps), "-i", os.path.join(frames, "%04d.png"),
                        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", mp4], check=True)
        print(mp4)
    else:
        for t in args.times or [0.3, 0.9, 1.4, 1.8, 2.3, 2.9, 3.3]:
            path = os.path.join(OUT, f"t{t:.2f}-{w}x{h}.png")
            render(t, w, h).save(path)
            print(path)
