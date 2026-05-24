"""Render the iOS App Store icon (square corners, no alpha) from the
geometry described in `reticulum_icon_ios_square.svg`. Re-exported by
hand from the SVG instead of via librsvg/Cairo so the build is
reproducible on a vanilla Windows + Pillow install — no native lib
dependencies. Run from the repo root:

    python iosApp/branding/render_ios_icon.py

Writes the result to
`iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`,
which the asset catalog's Contents.json already points at. The output
is 1024 x 1024, 8-bit sRGB RGB, no alpha channel — Apple's hard
requirements for the App Store icon slot (see
`iosApp/AppStore/icon-1024-spec.md`).
"""

from __future__ import annotations

import math
from pathlib import Path

from PIL import Image, ImageDraw

SIZE = 1024
CENTER = SIZE // 2
OUT = Path(__file__).resolve().parents[1] / "iosApp" / "Assets.xcassets" / "AppIcon.appiconset" / "AppIcon-1024.png"


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))  # type: ignore[return-value]


def _bg_gradient() -> Image.Image:
    """Diagonal gradient #0d2329 (top-left) → #061418 (bottom-right)
    matching the SVG's <linearGradient id="bg">."""
    top_left = (0x0d, 0x23, 0x29)
    bottom_right = (0x06, 0x14, 0x18)
    img = Image.new("RGB", (SIZE, SIZE), top_left)
    px = img.load()
    # Diagonal: t = (x + y) / (2 * (SIZE - 1)).
    denom = 2.0 * (SIZE - 1)
    for y in range(SIZE):
        for x in range(SIZE):
            t = (x + y) / denom
            px[x, y] = _lerp(top_left, bottom_right, t)
    return img


def _apply_radial_glow(img: Image.Image) -> None:
    """Overlay the #1abc9c centred radial glow at 25% peak alpha,
    matching the SVG's <radialGradient id="glow"> (r = 0.5 of bounds)."""
    glow_color = (0x1a, 0xbc, 0x9c)
    max_r = SIZE / 2.0
    px = img.load()
    for y in range(SIZE):
        dy = y - CENTER
        for x in range(SIZE):
            dx = x - CENTER
            d = math.hypot(dx, dy)
            if d >= max_r:
                continue
            # Linear falloff from 0.25 at centre to 0 at the edge.
            a = 0.25 * (1.0 - d / max_r)
            cur = px[x, y]
            px[x, y] = _lerp(cur, glow_color, a)


def render() -> None:
    img = _bg_gradient()
    _apply_radial_glow(img)

    draw = ImageDraw.Draw(img)

    # Six hexagon vertices, scaled 2× from the 512-viewBox SVG.
    vertices = [
        (512, 192),
        (792, 352),
        (792, 672),
        (512, 832),
        (232, 672),
        (232, 352),
    ]
    hub = (512, 512)
    teal = (0x1a, 0xbc, 0x9c)
    teal_light = (0x7c, 0xe9, 0xd3)
    bg_dark = (0x0d, 0x23, 0x29)
    stroke_w = 7

    # Outer hexagon edges (cyclic).
    for i in range(len(vertices)):
        a = vertices[i]
        b = vertices[(i + 1) % len(vertices)]
        draw.line([a, b], fill=teal, width=stroke_w)

    # Hub spokes — every vertex back to the centre.
    for v in vertices:
        draw.line([hub, v], fill=teal, width=stroke_w)

    # Cross-mesh accents @ 0.35 opacity. Pillow's draw.line has no
    # alpha; pre-mix the colour against the local background instead.
    # The accents sit on top of the gradient + glow but never on top
    # of other strokes (their endpoints are vertices, drawn after).
    accent = _lerp((0, 0, 0), teal, 0.35)
    # Approximate: paint the accents straight in the muted teal. The
    # background at the line midpoint is dark enough that this matches
    # the SVG's 35%-opacity-on-gradient appearance closely.
    accents = [
        ((512, 192), (232, 672)),
        ((512, 192), (792, 672)),
        ((792, 352), (232, 672)),
        ((232, 352), (792, 672)),
    ]
    for a, b in accents:
        draw.line([a, b], fill=accent, width=stroke_w)

    # Vertex circles: solid teal r=28, with a teal-light ring r=36 at
    # 60% opacity — approximate the same way as the accents.
    ring = _lerp((0, 0, 0), teal_light, 0.6)
    for cx, cy in vertices:
        # Outer ring (stroke only).
        draw.ellipse(
            [cx - 36, cy - 36, cx + 36, cy + 36],
            outline=ring,
            width=4,
        )
        # Solid teal centre.
        draw.ellipse(
            [cx - 28, cy - 28, cx + 28, cy + 28],
            fill=teal,
        )

    # Central hub: r=52 dark fill with an 8px teal stroke, then a
    # small r=20 teal dot inside.
    hx, hy = hub
    draw.ellipse(
        [hx - 52, hy - 52, hx + 52, hy + 52],
        fill=bg_dark,
        outline=teal,
        width=8,
    )
    draw.ellipse(
        [hx - 20, hy - 20, hx + 20, hy + 20],
        fill=teal,
    )

    OUT.parent.mkdir(parents=True, exist_ok=True)
    # Mode "RGB" — no alpha channel. Apple rejects icons with alpha.
    img.save(OUT, format="PNG", optimize=True)
    print(f"Wrote {OUT} ({SIZE}x{SIZE}, no alpha)")


if __name__ == "__main__":
    render()
