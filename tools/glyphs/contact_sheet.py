#!/usr/bin/env python3
"""Review sheet for Strata glyphs (FR-THEME-35, T-THEME-55 visual review).

Converts Android vector drawables (glyph_<id>.xml) to SVG and renders a contact sheet: every glyph
at 48 px and 24 px, on a dark and on a light background, labelled with its id. Large sets are split
into several pages.

    python3 tools/glyphs/contact_sheet.py OUT.png DIR_OR_FILE... [--per-page 40] [--cols 8] [--svg-dir D]

Needs `cairosvg` and Pillow (review tooling only, never part of the app build). Writes OUT.png, or
OUT-1.png, OUT-2.png, ... when there is more than one page.
"""

import argparse
import io
import os
import re
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"


def to_svg(xml_text, colour):
    root = ET.fromstring(xml_text)
    vw = root.get(A + "viewportWidth", "24")
    vh = root.get(A + "viewportHeight", "24")
    paths = []
    for p in root.iter("path"):
        rule = "evenodd" if p.get(A + "fillType", "nonZero").lower() == "evenodd" else "nonzero"
        paths.append(f'<path d="{p.get(A + "pathData")}" fill="{colour}" fill-rule="{rule}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" width="{vw}" height="{vh}">'
            + "".join(paths) + "</svg>")


def render(svg, px):
    import cairosvg
    from PIL import Image

    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def collect(inputs):
    files = []
    for item in inputs:
        if os.path.isdir(item):
            files += [os.path.join(item, f) for f in os.listdir(item) if re.match(r"glyph_.*\.xml$", f)]
        else:
            files.append(item)
    return sorted(files, key=lambda f: os.path.basename(f))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("--per-page", type=int, default=40)
    ap.add_argument("--cols", type=int, default=8)
    ap.add_argument("--svg-dir")
    args = ap.parse_args()

    from PIL import Image, ImageDraw, ImageFont

    files = collect(args.inputs)
    if not files:
        sys.exit("contact_sheet: no glyph_*.xml found")
    dark_bg, dark_fg, light_bg, light_fg = (24, 24, 28), "#F2F2F2", (246, 246, 242), "#1A1A1A"
    cw, ch = 176, 104
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 12)
    except OSError:
        font = ImageFont.load_default()
    pages = [files[i:i + args.per_page] for i in range(0, len(files), args.per_page)]
    stem, ext = os.path.splitext(args.out)
    written = []
    for n, page in enumerate(pages, 1):
        rows = (len(page) + args.cols - 1) // args.cols
        sheet = Image.new("RGB", (cw * args.cols, ch * rows), (128, 128, 128))
        d = ImageDraw.Draw(sheet)
        for i, f in enumerate(page):
            gid = re.sub(r"^glyph_|\.xml$", "", os.path.basename(f))
            text = open(f, encoding="utf-8").read()
            if args.svg_dir:
                os.makedirs(args.svg_dir, exist_ok=True)
                open(os.path.join(args.svg_dir, gid + ".svg"), "w").write(to_svg(text, "#000"))
            x0, y0 = (i % args.cols) * cw, (i // args.cols) * ch
            d.rectangle([x0 + 1, y0 + 1, x0 + cw // 2 - 1, y0 + ch - 20], fill=dark_bg)
            d.rectangle([x0 + cw // 2, y0 + 1, x0 + cw - 2, y0 + ch - 20], fill=light_bg)
            for half, (bg, fg) in enumerate([(dark_bg, dark_fg), (light_bg, light_fg)]):
                hx = x0 + half * (cw // 2)
                sheet.paste(render(to_svg(text, fg), 48), (hx + 6, y0 + 16), render(to_svg(text, fg), 48))
                sheet.paste(render(to_svg(text, fg), 24), (hx + 58, y0 + 28), render(to_svg(text, fg), 24))
            d.rectangle([x0 + 1, y0 + ch - 19, x0 + cw - 2, y0 + ch - 2], fill=(60, 60, 64))
            d.text((x0 + 6, y0 + ch - 17), gid, fill=(255, 255, 255), font=font)
        path = args.out if len(pages) == 1 else f"{stem}-{n}{ext}"
        sheet.save(path)
        written.append(path)
    print("\n".join(written))


if __name__ == "__main__":
    main()
