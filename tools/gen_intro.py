#!/usr/bin/env python3
"""
Generates VYRK's full opening: the vector (res/drawable/intro_vector.xml), its animation
(res/drawable/intro_full.xml + res/animator/intro_full_*.xml) and its sound logo
(res/raw/intro.wav). The 1-second sting (intro_sting*) reuses the same vector and is left alone.

The opening, about 3.7 s:
  0.0  Two gold hairlines draw a ring around where the V will be.
  0.25 The Elder Futhark is inscribed round the ring, clockwise from the top, in its three
       ættir of eight runes, each set opened by a ⁝ divider (as on the old rune stones).
  0.65 The two blades of the V slide in along their own lines and lock; the keystone drops.
  1.3  On the lock: a deep strike, a ring of light pulses out, the rim flashes,
       and a glint of light runs round the inscription.
  1.45 A sheen crosses the gold; VYRK is drawn beneath the ring.
  2.15 The gold and the black inside the V fade, so the V becomes a window onto Home,
  2.45 and the camera dives through it into the app.

Sound: a low drone, a lur (Norse horn) call, the strike with a ringing metal overtone,
a high glint, and a rush of air for the dive. Synthesised here; no samples.

Run from the repository root:  python3 tools/gen_intro.py
"""
import math
import os
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ANDROID = 'xmlns:android="http://schemas.android.com/apk/res/android"'

# ------------------------------------------------------------------ geometry (the mark's 0-100 box)
CX, CY = 50.0, 51.0                 # centre of the V, and of the ring
R_OUT, R_IN, R_RUNES = 77.0, 63.0, 70.0
GLYPH = 8.5                         # rune height
SCALE, TX, TY = 3, 810, 358         # the box is drawn at 3x, centred on a 1920x1080 screen
PIVOT = (930, 523)                  # the dive's centre: inside the left blade's window

V_LEFT = "M8,14 L30,14 L54,70 L50,88 Z"
V_RIGHT = "M92,14 L70,14 L56,47 L63,63 Z"
V_KEY = "M40,14 L60,14 L50,36 Z"

# Elder Futhark staves, drawn as strokes in a box 1 high (x to the right, y down).
DOT = 0.07   # the divider "dots" are tiny rounded dashes
RUNES = {
    "sep": [[(0, 0.3), (0, 0.3 + DOT)], [(0, 0.63), (0, 0.63 + DOT)]],
    "fehu": [[(0, 0), (0, 1)], [(0, 0.32), (0.5, 0.06)], [(0, 0.6), (0.5, 0.34)]],
    "uruz": [[(0, 1), (0, 0), (0.5, 0.32), (0.5, 1)]],
    "thurisaz": [[(0, 0), (0, 1)], [(0, 0.25), (0.42, 0.5), (0, 0.75)]],
    "ansuz": [[(0, 0), (0, 1)], [(0, 0.06), (0.48, 0.32)], [(0, 0.36), (0.48, 0.62)]],
    "raido": [[(0, 1), (0, 0), (0.46, 0.24), (0, 0.48), (0.46, 1)]],
    "kaunan": [[(0.42, 0.08), (0, 0.3), (0.42, 0.52)]],
    "gebo": [[(0, 0.1), (0.56, 0.9)], [(0.56, 0.1), (0, 0.9)]],
    "wunjo": [[(0, 1), (0, 0), (0.42, 0.22), (0, 0.44)]],
    "hagalaz": [[(0, 0), (0, 1)], [(0.5, 0), (0.5, 1)], [(0, 0.36), (0.5, 0.64)]],
    "naudiz": [[(0.25, 0), (0.25, 1)], [(0, 0.36), (0.5, 0.62)]],
    "isaz": [[(0, 0), (0, 1)]],
    "jera": [[(0.26, 0.06), (0, 0.3), (0.26, 0.54)], [(0.3, 0.46), (0.56, 0.7), (0.3, 0.94)]],
    "eihwaz": [[(0.26, 0), (0.26, 1)], [(0.26, 0), (0.52, 0.22)], [(0.26, 1), (0, 0.78)]],
    "perthro": [[(0, 0), (0, 1)], [(0, 0), (0.42, 0.26), (0.42, 0.08)], [(0, 1), (0.42, 0.74), (0.42, 0.92)]],
    "algiz": [[(0.3, 0), (0.3, 1)], [(0, 0.06), (0.3, 0.42), (0.6, 0.06)]],
    "sowilo": [[(0.46, 0), (0, 0.36), (0.46, 0.64), (0, 1)]],
    "tiwaz": [[(0.3, 0), (0.3, 1)], [(0, 0.3), (0.3, 0), (0.6, 0.3)]],
    "berkano": [[(0, 0), (0, 1)], [(0, 0), (0.44, 0.25), (0, 0.5), (0.44, 0.75), (0, 1)]],
    "ehwaz": [[(0, 1), (0, 0), (0.28, 0.34), (0.56, 0), (0.56, 1)]],
    "mannaz": [[(0, 0), (0, 1)], [(0.56, 0), (0.56, 1)], [(0, 0), (0.56, 0.42)], [(0.56, 0), (0, 0.42)]],
    "laguz": [[(0, 1), (0, 0), (0.44, 0.26)]],
    "ingwaz": [[(0.28, 0.22), (0.54, 0.5), (0.28, 0.78), (0.02, 0.5), (0.28, 0.22)]],
    "dagaz": [[(0, 0), (0, 1), (0.6, 0), (0.6, 1), (0, 0)]],
    "othala": [[(0, 1), (0.6, 0.36), (0.3, 0), (0, 0.36), (0.6, 1)]],
}
# The futhark in its three ættir, each opened by a divider: 27 places, so the dividers sit at
# 12, 4 and 8 o'clock.
INSCRIPTION = (["sep", "fehu", "uruz", "thurisaz", "ansuz", "raido", "kaunan", "gebo", "wunjo"]
               + ["sep", "hagalaz", "naudiz", "isaz", "jera", "eihwaz", "perthro", "algiz", "sowilo"]
               + ["sep", "tiwaz", "berkano", "ehwaz", "mannaz", "laguz", "ingwaz", "dagaz", "othala"])


def f(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def inscription_path():
    out = []
    n = len(INSCRIPTION)
    for k, name in enumerate(INSCRIPTION):
        phi = math.radians(-90 + k * 360.0 / n)
        ux, uy = math.cos(phi), math.sin(phi)      # outwards
        tx, ty = -math.sin(phi), math.cos(phi)     # clockwise
        strokes = RUNES[name]
        w = max(x for s in strokes for x, _ in s)
        for stroke in strokes:
            pts = []
            for x, y in stroke:
                a = (x - w / 2) * GLYPH
                b = (y - 0.5) * GLYPH
                px = CX + R_RUNES * ux + a * tx - b * ux
                py = CY + R_RUNES * uy + a * ty - b * uy
                pts.append(f"{f(px)},{f(py)}")
            out.append("M" + " L".join(pts))
    return " ".join(out)


def circle(r, cx=CX, cy=CY):
    top, bottom = cy - r, cy + r
    return f"M{f(cx)},{f(top)} A{f(r)},{f(r)} 0 0,1 {f(cx)},{f(bottom)} A{f(r)},{f(r)} 0 0,1 {f(cx)},{f(top)}"


# A soft warm light behind the V: stacked faint discs (vector gradients need Android 7; VYRK runs on 6).
GLOW_RADII = list(range(18, 76, 5))


def glow_xml():
    return "\n".join(
        f'                <path android:name="glow{i}" android:fillColor="#05D4A65A" android:fillAlpha="0" '
        f'android:pathData="{circle(r)}" />' for i, r in enumerate(GLOW_RADII))


# VYRK in thin geometric capitals under the ring: cap height 9, letters 8/8/6.5/6.5 wide, 5.5 apart.
WORDMARK = ("M27.25,138 L31.25,147 L35.25,138 "
            "M40.75,138 L44.75,142.5 L48.75,138 M44.75,142.5 L44.75,147 "
            "M54.25,147 L54.25,138 L58.25,138 A2.4,2.4 0 0,1 58.25,142.8 L54.25,142.8 M57.25,142.8 L60.75,147 "
            "M66.25,138 L66.25,147 M72.75,138 L66.25,143.5 M68.6,141.6 L72.75,147")


def vector_xml():
    holes = "M8,14 L50,88 L54,70 L30,14 Z M92,14 L70,14 L56,47 L63,63 Z"
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- VYRK's opening, drawn as a vector so it animates on the render thread (smooth even while the app
     starts up underneath). Generated by tools/gen_intro.py: edit that and run it, not this file.
     A black screen with a V-shaped hole covered by the gold V; round it a ring inscribed with the
     Elder Futhark, and VYRK beneath. The full opening (intro_full) draws all of it; the sting
     (intro_sting) only lights the V. Both end with the gold fading and the camera diving through
     the hole into the app. -->
<vector {ANDROID}
    android:width="1920dp" android:height="1080dp"
    android:viewportWidth="1920" android:viewportHeight="1080">
    <group android:name="zoom" android:pivotX="{PIVOT[0]}" android:pivotY="{PIVOT[1]}">
        <group android:translateX="{TX}" android:translateY="{TY}" android:scaleX="{SCALE}" android:scaleY="{SCALE}">
            <group android:name="appear" android:pivotX="50" android:pivotY="60.5" android:scaleX="0.94" android:scaleY="0.94">
                <path android:name="mask" android:fillType="evenOdd" android:fillColor="#FF000000"
                    android:pathData="M-5000,-5000 L5100,-5000 L5100,5100 L-5000,5100 Z {holes}" />
                <path android:name="veil" android:fillColor="#FF000000" android:pathData="{V_LEFT} {V_RIGHT}" />
{glow_xml()}
                <path android:name="ringOuter" android:strokeColor="#FFB08A4E" android:strokeWidth="0.45"
                    android:strokeLineCap="round" android:trimPathEnd="0" android:pathData="{circle(R_OUT)}" />
                <path android:name="ringInner" android:strokeColor="#FF8C6E40" android:strokeWidth="0.3"
                    android:strokeLineCap="round" android:trimPathEnd="0" android:pathData="{circle(R_IN)}" />
                <path android:name="runes" android:strokeColor="#FFC49A5A" android:strokeWidth="0.55"
                    android:strokeLineCap="round" android:strokeLineJoin="round" android:trimPathEnd="0"
                    android:pathData="{inscription_path()}" />
                <path android:name="runesGlint" android:strokeColor="#FFFFF3D6" android:strokeWidth="0.75"
                    android:strokeLineCap="round" android:strokeLineJoin="round" android:strokeAlpha="0"
                    android:trimPathStart="0" android:trimPathEnd="0.07"
                    android:pathData="{inscription_path()}" />
                <group android:name="pulse" android:pivotX="{f(CX)}" android:pivotY="{f(CY)}">
                    <path android:name="pulseRing" android:strokeColor="#FFF3DEB0" android:strokeWidth="0.35"
                        android:strokeAlpha="0" android:pathData="{circle(24)}" />
                </group>
                <group android:name="leftIn">
                    <path android:name="goldLeft" android:fillColor="#FFD4A65A" android:fillAlpha="0" android:pathData="{V_LEFT}" />
                </group>
                <group android:name="rightIn">
                    <path android:name="goldRight" android:fillColor="#FFF3DEB0" android:fillAlpha="0" android:pathData="{V_RIGHT}" />
                </group>
                <group android:name="runeIn">
                    <path android:name="rune" android:fillColor="#FFA57C3C" android:fillAlpha="0" android:pathData="{V_KEY}" />
                </group>
                <group android:name="sheenClip">
                    <clip-path android:pathData="{V_LEFT} {V_RIGHT}" />
                    <group android:name="sheen" android:translateX="-45">
                        <path android:fillColor="#FFFFFFFF" android:fillAlpha="0.75" android:pathData="M-10,-10 L8,-10 L38,110 L20,110 Z" />
                    </group>
                </group>
                <path android:name="rim" android:strokeColor="#FFD4A65A" android:strokeWidth="0.7" android:strokeAlpha="0"
                    android:strokeLineJoin="miter" android:pathData="{V_LEFT} {V_RIGHT}" />
                <path android:name="wordmark" android:strokeColor="#FFE9D3A3" android:strokeWidth="0.7"
                    android:strokeLineCap="butt" android:strokeLineJoin="miter" android:trimPathEnd="0"
                    android:pathData="{WORDMARK}" />
            </group>
        </group>
    </group>
</vector>
'''


# ------------------------------------------------------------------ animation (times in ms)
TOTAL = 3750


def anim(prop, v0, v1, start, dur, interp="@android:interpolator/linear"):
    return (f'    <objectAnimator android:propertyName="{prop}" android:valueFrom="{v0}" android:valueTo="{v1}" '
            f'android:valueType="floatType"\n        android:duration="{dur}" android:startOffset="{start}" '
            f'android:interpolator="{interp}" />')


def keys(prop, points):
    """Linear keyframes over the whole opening; points are (ms, value), holding the last one."""
    if points[0][0] != 0:
        points = [(0, points[0][1])] + points
    if points[-1][0] != TOTAL:
        points = points + [(TOTAL, points[-1][1])]
    frames = "\n".join(f'            <keyframe android:fraction="{t / TOTAL:.4f}" android:value="{v}" />' for t, v in points)
    return (f'    <objectAnimator android:duration="{TOTAL}" android:interpolator="@android:interpolator/linear">\n'
            f'        <propertyValuesHolder android:propertyName="{prop}" android:valueType="floatType">\n'
            f'{frames}\n        </propertyValuesHolder>\n    </objectAnimator>')


def set_xml(*parts):
    return f'<?xml version="1.0" encoding="utf-8"?>\n<set {ANDROID}>\n' + "\n".join(parts) + "\n</set>\n"


FOSI = "@android:interpolator/fast_out_slow_in"
DEC2, DEC3, DEC5 = ("@android:interpolator/decelerate_quad", "@android:interpolator/decelerate_cubic",
                    "@android:interpolator/decelerate_quint")
ACCDEC = "@android:interpolator/accelerate_decelerate"


def fade(level):
    return [(0, level), (2400, level), (2750, 0)]


LOCK = 1300   # the V locks: strike, pulse, rim, glint
ANIMATORS = {
    "appear": set_xml(anim("scaleX", 0.94, 1, 0, 2400, DEC3), anim("scaleY", 0.94, 1, 0, 2400, DEC3)),
    "ring_outer": set_xml(anim("trimPathEnd", 0, 1, 0, 900, FOSI), keys("strokeAlpha", fade(1))),
    "ring_inner": set_xml(anim("trimPathEnd", 0, 1, 150, 900, FOSI), keys("strokeAlpha", fade(1))),
    "runes": set_xml(anim("trimPathEnd", 0, 1, 250, 1100, DEC2), keys("strokeAlpha", fade(0.85))),
    "glint": set_xml(anim("trimPathOffset", 0, 0.93, LOCK - 50, 900, ACCDEC),
                     keys("strokeAlpha", [(0, 0), (LOCK - 50, 0), (LOCK + 50, 1), (2000, 1), (2150, 0)])),
    "pulse": set_xml(anim("scaleX", 1, 3.2, LOCK, 650, DEC3), anim("scaleY", 1, 3.2, LOCK, 650, DEC3)),
    "pulse_ring": set_xml(keys("strokeAlpha", [(0, 0), (LOCK, 0), (LOCK + 30, 0.6), (LOCK + 650, 0)])),
    "left_in": set_xml(anim("translateX", -4.5, 0, 650, 600, DEC5), anim("translateY", -8.9, 0, 650, 600, DEC5)),
    "right_in": set_xml(anim("translateX", 4.6, 0, 750, 550, DEC5), anim("translateY", -8.8, 0, 750, 550, DEC5)),
    "rune_in": set_xml(anim("translateY", -7, 0, 1050, 350, DEC3)),
    "gold_left": set_xml(keys("fillAlpha", [(0, 0), (650, 0), (950, 1), (2250, 1), (2750, 0)])),
    "gold_right": set_xml(keys("fillAlpha", [(0, 0), (750, 0), (1050, 1), (2250, 1), (2750, 0)])),
    "rune": set_xml(keys("fillAlpha", [(0, 0), (1050, 0), (1250, 1), (2250, 1), (2650, 0)])),
    "glow": set_xml(keys("fillAlpha", [(0, 0), (650, 0), (LOCK, 1), (2300, 1), (2700, 0)])),
    "veil": set_xml(keys("fillAlpha", [(0, 1), (2150, 1), (2650, 0)])),
    "rim": set_xml(keys("strokeAlpha", [(0, 0), (LOCK, 0), (LOCK + 80, 0.9), (LOCK + 200, 0.9), (LOCK + 550, 0)])),
    "sheen": set_xml(anim("translateX", -45, 115, 1450, 600, ACCDEC)),
    "wordmark": set_xml(anim("trimPathEnd", 0, 1, 1500, 650, DEC3),
                        keys("strokeAlpha", [(0, 0), (1500, 0), (1600, 1), (2400, 1), (2700, 0)])),
    "zoom": set_xml(anim("scaleX", 1, 140, 2450, 1300, "@animator/intro_zoom_curve"),
                    anim("scaleY", 1, 140, 2450, 1300, "@animator/intro_zoom_curve"),
                    anim("translateX", 0, 30, 2450, 1300, DEC2),
                    anim("translateY", 0, -15, 2450, 1300, DEC2)),
}
TARGETS = [("appear", "appear"), ("ringOuter", "ring_outer"), ("ringInner", "ring_inner"), ("runes", "runes"),
           ("runesGlint", "glint"), ("pulse", "pulse"), ("pulseRing", "pulse_ring"), ("leftIn", "left_in"),
           ("rightIn", "right_in"), ("runeIn", "rune_in"), ("goldLeft", "gold_left"), ("goldRight", "gold_right"),
           ("rune", "rune"), ("veil", "veil"), ("rim", "rim"), ("sheen", "sheen"), ("wordmark", "wordmark"),
           ("zoom", "zoom")] + [(f"glow{i}", "glow") for i in range(len(GLOW_RADII))]


def avd_xml():
    rows = "\n".join(f'    <target android:name="{n}" android:animation="@animator/intro_full_{a}" />' for n, a in TARGETS)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/gen_intro.py. -->\n'
            f'<animated-vector {ANDROID} android:drawable="@drawable/intro_vector">\n{rows}\n</animated-vector>\n')


# ------------------------------------------------------------------ sound
def sound(path, sr=22050):
    import numpy as np
    dur = 4.3
    n = int(sr * dur)
    t = np.arange(n) / sr
    rng = np.random.default_rng(7)

    def smooth(x):
        x = np.clip(x, 0, 1)
        return x * x * (3 - 2 * x)

    def after(t0):
        return np.clip(t - t0, 0, None)

    def lowpass(x, cutoff):
        a = np.exp(-2 * np.pi * cutoff / sr) if np.isscalar(cutoff) else None
        y = np.zeros_like(x)
        prev = 0.0
        for i in range(len(x)):
            c = a if a is not None else np.exp(-2 * np.pi * cutoff[i] / sr)
            prev = (1 - c) * x[i] + c * prev
            y[i] = prev
        return y

    out = np.zeros(n)

    # Drone: A1 with a slow beat, the fifth and the octave; swells in, holds, fades through the dive.
    drone_env = smooth(t / 1.3) * np.where(t < 2.4, 1.0, np.exp(-(t - 2.4) / 0.55))
    drone = (0.5 * np.sin(2 * np.pi * 55 * t) + 0.3 * np.sin(2 * np.pi * 55.35 * t)
             + 0.22 * np.sin(2 * np.pi * 82.5 * t) + 0.12 * np.sin(2 * np.pi * 110 * t))
    out += 0.26 * drone_env * drone

    # Lur: a brass call on A2 that bends up into pitch, with the fifth joining; brighter as it swells.
    def horn(f0, start, length, gain):
        local = after(start)
        on = (t >= start) & (t < start + length + 0.6)
        env = smooth(local / 0.28) * np.where(local < length, 1.0, np.exp(-(local - length) / 0.22)) * on
        freq = f0 * (1 - 0.035 * np.exp(-local / 0.09)) * (1 + 0.004 * np.sin(2 * np.pi * 5.1 * local))
        phase = 2 * np.pi * np.cumsum(freq) / sr
        tone = np.zeros(n)
        for h in range(1, 11):
            tone += (1 / h ** 1.1) * env ** (0.6 + 0.18 * h) * np.sin(h * phase)
        return gain * tone

    out += horn(110, 0.2, 1.05, 0.11)
    out += horn(165, 0.5, 0.75, 0.055)

    lock = 1.3
    # Strike: a falling sub thump, a short dark transient, and a ringing metal overtone.
    local = after(lock)
    on = t >= lock
    sweep = 36 + 59 * np.exp(-local / 0.12)
    thump = np.sin(2 * np.pi * np.cumsum(sweep * on) / sr) * np.exp(-local / 0.3) * on
    out += 0.62 * thump
    burst = rng.standard_normal(n) * np.exp(-local / 0.045) * on
    out += 0.3 * lowpass(burst, 900.0)
    for fr, amp, decay in [(392, 1.0, 1.6), (988.6, 0.6, 1.1), (1503, 0.45, 0.8), (2251, 0.3, 0.6), (3011, 0.2, 0.4)]:
        out += 0.075 * amp * np.sin(2 * np.pi * fr * local) * np.exp(-local / decay) * on

    # Glint: two high partials with a shimmer, following the light round the runes.
    g = after(lock - 0.05)
    genv = smooth(g / 0.12) * np.exp(-g / 0.55) * (t >= lock - 0.05)
    shimmer = 0.75 + 0.25 * np.sin(2 * np.pi * 14 * g)
    out += 0.03 * genv * shimmer * (np.sin(2 * np.pi * 2637 * t) + 0.6 * np.sin(2 * np.pi * 3951 * t))

    # The dive: a rush of air that brightens as the camera pushes through.
    d = after(2.35)
    denv = smooth(d / 0.6) * np.exp(-np.clip(d - 0.6, 0, None) / 0.25) * (t >= 2.35)
    noise = rng.standard_normal(n)
    cutoff = 300 + 3200 * smooth(d / 1.0)
    band = lowpass(noise, cutoff) - lowpass(noise, cutoff * 0.35)
    out += 0.16 * denv * band

    # A small hall: four damped combs and two all-passes (Schroeder).
    def comb(x, delay, fb, damp):
        y = np.zeros_like(x)
        buf = np.zeros(delay)
        store, idx = 0.0, 0
        for i in range(len(x)):
            o = buf[idx]
            store = o * (1 - damp) + store * damp
            buf[idx] = x[i] + store * fb
            idx = (idx + 1) % delay
            y[i] = o
        return y

    def allpass(x, delay, gain=0.5):
        y = np.zeros_like(x)
        buf = np.zeros(delay)
        idx = 0
        for i in range(len(x)):
            b = buf[idx]
            y[i] = -x[i] + b
            buf[idx] = x[i] + b * gain
            idx = (idx + 1) % delay
        return y

    wet = sum(comb(out, d_, 0.8, 0.25) for d_ in (558, 594, 639, 678)) / 4
    wet = allpass(allpass(wet, 278), 113)
    mix = out + 0.28 * wet

    mix = np.tanh(1.15 * mix) / np.tanh(1.15)
    mix *= 0.89 / np.max(np.abs(mix))
    tail = int(0.35 * sr)
    mix[-tail:] *= np.linspace(1, 0, tail) ** 2
    pcm = (mix * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def write(rel, text):
    with open(os.path.join(RES, rel), "w", encoding="utf-8") as fh:
        fh.write(text)


if __name__ == "__main__":
    write("drawable/intro_vector.xml", vector_xml())
    write("drawable/intro_full.xml", avd_xml())
    adir = os.path.join(RES, "animator")
    for name in os.listdir(adir):
        if name.startswith("intro_full_"):
            os.remove(os.path.join(adir, name))
    for name, xml in ANIMATORS.items():
        write(f"animator/intro_full_{name}.xml", xml)
    sound(os.path.join(RES, "raw", "intro.wav"))
    print("Wrote the opening: vector, %d animators, sound." % len(ANIMATORS))
