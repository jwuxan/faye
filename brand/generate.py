"""Faye textmark: "Faye" set in Playwrite Italia Tradizionale, as SVG paths, light and dark.

The font is not in the repo (AGENTS.md rule 7). This script downloads it from google/fonts at a pinned
commit, checks its SHA-256, shapes "Faye" with HarfBuzz (so the script's joins are kept), tightens the
tracking, and writes the outlines as paths centred on their ink box. PNGs are rendered from the SVGs by
headless Chrome (CHROME overrides its path).

    python3 -m venv brand/.venv && brand/.venv/bin/pip install 'fonttools==4.*' 'uharfbuzz==0.*'
    brand/.venv/bin/python -I brand/generate.py
"""

import hashlib
import io
import os
import pathlib
import subprocess
import sys
import tempfile
import urllib.request

import uharfbuzz as hb
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

COMMIT = "e89e4846eecfa1b28451ec91f7909462988657d1"
URL = f"https://raw.githubusercontent.com/google/fonts/{COMMIT}/ofl/playwriteittrad/PlaywriteITTrad%5Bwght%5D.ttf"
SHA256 = "51104d891204c4446a326af5c052de5fd9e963b76321cb3ad331144e05b59d8e"

TEXT = "Faye"
WEIGHT = 400  # the axis maximum
# Tracking in font units (1000 per em), by letter pair. Negative, so the word reads as one mark, but only
# where letters do not join: a joined pair (a-y, y-e) set tighter shows the ends of its connecting strokes.
# At -160 the F's crossbar tucks over the a without touching it.
TRACKING = {("F", "a"): -160}
PAD = 0.08  # margin around the ink, as a fraction of its height
INK = {"light": "#171717", "dark": "#fafafa"}  # for light and dark backgrounds: the theme's ink colours
PNG_WIDTH = 4096
ICON_PNG = 1024
TOUCH_ICON = 180

HERE = pathlib.Path(__file__).resolve().parent
CACHE = HERE / ".cache"  # git ignored
ADMIN = HERE.parent / "web" / "apps" / "admin" / "app"
COMPONENT = HERE.parent / "web" / "packages" / "ui" / "src" / "components" / "faye-logo.tsx"
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")


def num(v: float) -> str:
    return f"{v:.1f}".rstrip("0").rstrip(".")


def font_bytes() -> bytes:
    path = CACHE / "PlaywriteITTrad.ttf"
    if not path.exists():
        CACHE.mkdir(exist_ok=True)
        with urllib.request.urlopen(URL) as r:  # noqa: S310, fixed https URL
            path.write_bytes(r.read())
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != SHA256:
        sys.exit(f"checksum mismatch for {path}")
    return data


def outline(data: bytes) -> tuple[str, tuple[float, float, float, float]]:
    """The word's path (y down, font units) and its ink box (x0, y0, x1, y1)."""
    font = hb.Font(hb.Face(data))
    font.set_variations({"wght": WEIGHT})
    buf = hb.Buffer()
    buf.add_str(TEXT)
    buf.guess_segment_properties()
    hb.shape(font, buf, {"kern": True, "liga": True, "calt": True})

    tt = instantiateVariableFont(TTFont(io.BytesIO(data)), {"wght": WEIGHT})
    glyphs = tt.getGlyphSet()
    order = tt.getGlyphOrder()
    pen = SVGPathPen(glyphs, ntos=num)
    bounds = BoundsPen(glyphs)
    x = 0.0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        name = order[info.codepoint]
        t = (1, 0, 0, -1, x + pos.x_offset, -pos.y_offset)  # flip y into SVG space
        glyphs[name].draw(TransformPen(pen, t))
        glyphs[name].draw(TransformPen(bounds, t))
        pair = (TEXT[info.cluster], TEXT[info.cluster + 1 : info.cluster + 2])
        x += pos.x_advance + TRACKING.get(pair, 0)
    assert bounds.bounds is not None
    return pen.getCommands(), bounds.bounds


def view_box(box: tuple[float, float, float, float], square: bool) -> tuple[float, float, float, float]:
    """The ink box plus padding, the ink centred both ways."""
    x0, y0, x1, y1 = box
    w, h = x1 - x0, y1 - y0
    pad = PAD * h
    vw, vh = w + 2 * pad, h + 2 * pad
    if square:
        vw = vh = max(vw, vh)
    return x0 - (vw - w) / 2, y0 - (vh - h) / 2, vw, vh


def svg(path: str, vb: tuple[float, ...], fill: str, extra: str = "") -> str:
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{" ".join(map(num, vb))}" role="img" aria-label="Faye">'
        f'<title>Faye</title>{extra}<path fill="{fill}" d="{path}"/></svg>\n'
    )


def png(src: pathlib.Path, dst: pathlib.Path, width: int, height: int, background: str = "transparent") -> None:
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp) / "page.html"
        page.write_text(
            f'<html><body style="margin:0;background:{background}">'
            f'<img src="{src.as_uri()}" style="display:block;width:{width}px;height:{height}px"></body></html>'
        )
        subprocess.run(
            [
                CHROME, "--headless", "--disable-gpu", "--hide-scrollbars", "--allow-file-access-from-files",
                "--default-background-color=00000000", f"--window-size={width},{height}",
                f"--user-data-dir={tmp}/profile", f"--screenshot={dst}", page.as_uri(),
            ],
            check=True,
            capture_output=True,
        )


def main() -> None:
    path, box = outline(font_bytes())
    wide, square = view_box(box, False), view_box(box, True)
    height = round(PNG_WIDTH * wide[3] / wide[2])

    for theme, ink in INK.items():
        logo = HERE / f"faye-logo-{theme}.svg"
        logo.write_text(svg(path, wide, ink))
        png(logo, HERE / f"faye-logo-{theme}.png", PNG_WIDTH, height)
        icon = HERE / f"faye-icon-{theme}.svg"
        icon.write_text(svg(path, square, ink))
        png(icon, HERE / f"faye-icon-{theme}.png", ICON_PNG, ICON_PNG)

    # The admin's favicon follows the browser's colour scheme. Apple touch icons have no transparency,
    # so that one is the light mark on white.
    scheme = (
        f"<style>path{{fill:{INK['light']}}}"
        f"@media (prefers-color-scheme:dark){{path{{fill:{INK['dark']}}}}}</style>"
    )
    (ADMIN / "icon.svg").write_text(svg(path, square, INK["light"], scheme))
    png(HERE / "faye-icon-light.svg", ADMIN / "apple-icon.png", TOUCH_ICON, TOUCH_ICON, "#fff")

    # The inline mark: one colour, currentColor, so it follows the theme it sits in.
    COMPONENT.write_text(
        "// Generated by brand/generate.py: the Faye textmark (Playwrite Italia Tradizionale, as paths). Do not edit.\n"
        'import type { SVGProps } from "react";\n\n'
        "/** The Faye textmark in the current text colour. Size it by height; the width follows. */\n"
        "export function FayeLogo(props: SVGProps<SVGSVGElement>) {\n"
        "  return (\n"
        f'    <svg viewBox="{" ".join(map(num, wide))}" role="img" aria-label="Faye" {{...props}}>\n'
        f'      <path fill="currentColor" d="{path}" />\n'
        "    </svg>\n"
        "  );\n"
        "}\n"
    )
    print(f"ink {num(box[2] - box[0])} x {num(box[3] - box[1])} units; logo PNG {PNG_WIDTH} x {height}")


if __name__ == "__main__":
    main()
