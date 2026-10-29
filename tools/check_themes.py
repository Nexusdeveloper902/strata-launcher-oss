"""Validate Strata sample themes against spec/theme.schema.json and check WCAG 2.2 AA
contrast per spec/THEME_SYSTEM.md section 7 (worst-case wallpaper, gamma-space alpha blending).
Usage: python check.py <schema.json> <theme files...>
"""
import json, sys
from jsonschema import Draft202012Validator

DEFAULTS = {
    "light": {"background": "#FAFAF7", "surface": "#EDECE6", "textPrimary": "#1B1B1A",
              "textSecondary": "#57564F", "accent": "#2F5D8A", "onAccent": "#FFFFFF",
              "divider": "#D6D4CC", "highlight": "#E3E1D9", "scrim": "#FFFFFF"},
    "dark": {"background": "#121212", "surface": "#1F1F1F", "textPrimary": "#ECECEA",
             "textSecondary": "#B0B0AA", "accent": "#8DB4DE", "onAccent": "#0B2239",
             "divider": "#33332F", "highlight": "#2B2B28", "scrim": "#000000"},
}
WP_DEFAULT = {"show": False, "dim": 0.5, "backgroundOpacity": 0.0}

# (foreground role, background role or "EFFECTIVE", minimum ratio)
PAIRS = [
    ("textPrimary", "EFFECTIVE", 4.5), ("textSecondary", "EFFECTIVE", 4.5),
    ("accent", "EFFECTIVE", 3.0), ("textPrimary", "surface", 4.5),
    ("textSecondary", "surface", 4.5), ("accent", "surface", 3.0),
    ("textPrimary", "highlight", 4.5), ("onAccent", "accent", 4.5),
]


def rgb(h):
    return tuple(int(h[i:i + 2], 16) / 255 for i in (1, 3, 5))


def lin(c):
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def lum(c):
    r, g, b = (lin(x) for x in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def ratio(l1, l2):
    hi, lo = max(l1, l2), min(l1, l2)
    return (hi + 0.05) / (lo + 0.05)


def over(top, alpha, bottom):  # gamma-space source-over, as Android/Compose blend by default
    return tuple(alpha * t + (1 - alpha) * b for t, b in zip(top, bottom))


def effective_range(pal, wp):
    """Return (Lmin, Lmax) of the effective background over all possible wallpapers."""
    bg = rgb(pal["background"])
    if not wp["show"]:
        l = lum(bg)
        return l, l
    out = []
    for w in ((0, 0, 0), (1, 1, 1)):  # per-channel linear in W => extremes bound the range
        c = over(rgb(pal["scrim"]), wp["dim"], w)
        c = over(bg, wp["backgroundOpacity"], c)
        out.append(lum(c))
    return min(out), max(out)


def worst_ratio(fg_l, rng):
    lo, hi = rng
    if lo <= fg_l <= hi:
        return 1.0
    return min(ratio(fg_l, lo), ratio(fg_l, hi))


def variants(theme):
    mode = theme.get("colors", {}).get("mode", "system")
    return {"light": ["light"], "dark": ["dark"]}.get(mode, ["light", "dark"])


def palette(theme, v, pure_black):
    p = dict(DEFAULTS[v])
    p.update({k: x for k, x in theme.get("colors", {}).get(v, {}).items() if not k.startswith("x-")})
    if v == "dark" and pure_black:
        p["background"] = "#000000"
        p["scrim"] = "#000000"
    return p


def check(theme):
    wp = dict(WP_DEFAULT)
    wp.update({k: x for k, x in theme.get("wallpaper", {}).items() if k in WP_DEFAULT})
    pb = theme.get("colors", {}).get("pureBlack", False)
    rows, ok = [], True
    cases = [(v, pb) for v in variants(theme)]
    if "dark" in variants(theme) and not pb:
        cases.append(("dark", True))  # toggling pure black must stay AA too
    for v, pbx in cases:
        pal = palette(theme, v, pbx)
        rng = effective_range(pal, wp)
        for fg, bg, need in PAIRS:
            fl = lum(rgb(pal[fg]))
            r = worst_ratio(fl, rng) if bg == "EFFECTIVE" else ratio(fl, lum(rgb(pal[bg])))
            passed = r >= need
            ok &= passed
            rows.append((f"{v}{'+pureBlack' if pbx else ''}", fg, bg, round(r, 2), need, passed))
    return ok, rows


def main():
    schema = json.load(open(sys.argv[1]))
    Draft202012Validator.check_schema(schema)
    val = Draft202012Validator(schema)
    all_ok = True
    files = sys.argv[2:]
    for f in ["<built-in defaults>"] + files:
        if f.startswith("<"):
            theme = {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "Default"}
        else:
            theme = json.load(open(f))
        errs = sorted(val.iter_errors(theme), key=lambda e: list(e.path))
        ok, rows = check(theme)
        all_ok &= ok and not errs
        name = theme.get("name")
        print(f"== {name} ({f.split('/')[-1]}): schema {'VALID' if not errs else 'INVALID'}; contrast {'PASS' if ok else 'FAIL'}")
        for e in errs:
            print("   schema error:", list(e.path), e.message)
        minimum = {}
        for v, fg, bg, r, need, p in rows:
            print(f"   {v:16} {fg:13} on {bg:10} {r:6.2f} (>= {need}) {'ok' if p else 'FAIL'}")
    # negative tests: the schema must reject these
    bad = [
        {"schemaVersion": 2, "id": "00000000-0000-4000-8000-000000000000", "name": "x"},
        {"schemaVersion": 1, "id": "nope", "name": "x"},
        {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "x", "colors": {"light": {"accent": "#12345"}}},
        {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "x", "typography": {"scale": 3}},
        {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "x", "typography": {"family": {"kind": "user"}}},
        {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "x", "unknownKey": 1},
    ]
    good_ext = {"schemaVersion": 1, "id": "00000000-0000-4000-8000-000000000000", "name": "x",
                "x-vendor.thing": {"a": 1}, "icons": {"x-foo": True, "pack": None}}
    neg_ok = all(not val.is_valid(b) for b in bad) and val.is_valid(good_ext)
    print(f"== negative/extension tests: {'PASS' if neg_ok else 'FAIL'} ({len(bad)} rejected cases, 1 accepted x- case)")
    all_ok &= neg_ok
    print("OVERALL:", "PASS" if all_ok else "FAIL")
    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
