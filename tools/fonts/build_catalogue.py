"""
Builds the Studio's bundled font set: downloads each family at a pinned version, checks or records its SHA-256,
copies its licence beside it, measures which scripts it draws from its character map, and writes
core/render/src/main/assets/fonts/catalogue.json.

    python tools/fonts/build_catalogue.py            # download what is missing, rewrite the catalogue
    python tools/fonts/build_catalogue.py --verify   # only check the files on disk against the pinned hashes

Needs fontTools (pip install fonttools). Every source is OFL 1.1; see NOTICE.
"""
import hashlib
import io
import json
import os
import sys
import urllib.request
import zipfile

from fontTools.ttLib import TTFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ASSETS = os.path.join(ROOT, "core", "render", "src", "main", "assets", "fonts")
RES_FONTS = os.path.join(ROOT, "core", "render", "src", "main", "res", "font")

GF = "https://raw.githubusercontent.com/google/fonts/9710da1eacb3be272583c3224dcb70f9da6eadbb/ofl/"
HUNINN = "https://raw.githubusercontent.com/justfont/open-huninn-font/f240f9071a78298f379bc087cae2010df11cf477/"
SMILEY_ZIP = "https://github.com/atelier-anchor/smiley-sans/releases/download/v2.0.1/smiley-sans-v2.0.1.zip"

# One sample line per script: a font "draws" a script when its cmap has every character of the line.
SAMPLES = {
    "latin": "The quick brown fox éàüñçßõ",
    "vietnamese": "Tiếng Việt ạảấầẩẫậắằẳẵặđ",
    "cyrillic": "Съешь же ещё этих мягких булок",
    "ja": "あいうえおアイウエオ漫画日本語の話",
    "ko": "한국어 만화 안녕하세요 대화",
    "zh-hans": "简体中文漫画这是说话们对",
    "zh-hant": "繁體中文漫畫這是說話們對",
    "thai": "ภาษาไทยการ์ตูนสวัสดี",
    "arabic": "العربية مانغا مرحبا",
    "devanagari": "हिन्दी कॉमिक नमस्ते",
}

# id, display name, role, tags, files {style: (url or zip member, file name)}, extra
FONTS = [
    ("comic_neue", "Comic Neue", "dialogue", ["comic", "handwritten", "rounded"], {
        "regular": GF + "comicneue/ComicNeue-Regular.ttf", "bold": GF + "comicneue/ComicNeue-Bold.ttf",
        "italic": GF + "comicneue/ComicNeue-Italic.ttf", "boldItalic": GF + "comicneue/ComicNeue-BoldItalic.ttf"},
     {"licenceUrl": GF + "comicneue/OFL.txt"}),
    ("bangers", "Bangers", "sfx", ["comic", "bold", "impact"], {"regular": GF + "bangers/Bangers-Regular.ttf"},
     {"licenceUrl": GF + "bangers/OFL.txt"}),
    ("shantell_sans", "Shantell Sans", "dialogue", ["comic", "handwritten", "marker"],
     {"regular": GF + "shantellsans/ShantellSans%5BBNCE,INFM,SPAC,wght%5D.ttf"},
     {"licenceUrl": GF + "shantellsans/OFL.txt", "file": "ShantellSans-Variable.ttf", "boldVariation": "'wght' 700"}),
    ("shantell_sans_extrabold", "Shantell Sans ExtraBold", "sfx", ["comic", "bold", "marker"],
     {"regular": "shantell_sans/ShantellSans-Variable.ttf"},
     {"shared": True, "variation": "'wght' 800"}),
    ("rubik_mono_one", "Rubik Mono One", "sfx", ["bold", "wide", "impact"], {"regular": GF + "rubikmonoone/RubikMonoOne-Regular.ttf"},
     {"licenceUrl": GF + "rubikmonoone/OFL.txt"}),
    ("zen_antique", "Zen Antique", "dialogue", ["manga", "antique", "print"], {"regular": GF + "zenantique/ZenAntique-Regular.ttf"},
     {"licenceUrl": GF + "zenantique/OFL.txt"}),
    ("dela_gothic_one", "Dela Gothic One", "sfx", ["bold", "impact", "manga"], {"regular": GF + "delagothicone/DelaGothicOne-Regular.ttf"},
     {"licenceUrl": GF + "delagothicone/OFL.txt"}),
    ("gaegu", "Gaegu", "dialogue", ["handwritten", "comic"], {
        "regular": GF + "gaegu/Gaegu-Regular.ttf", "bold": GF + "gaegu/Gaegu-Bold.ttf"},
     {"licenceUrl": GF + "gaegu/OFL.txt"}),
    ("black_han_sans", "Black Han Sans", "sfx", ["bold", "impact"], {"regular": GF + "blackhansans/BlackHanSans-Regular.ttf"},
     {"licenceUrl": GF + "blackhansans/OFL.txt"}),
    ("zcool_kuaile", "ZCOOL KuaiLe", "dialogue", ["handwritten", "rounded", "comic"], {"regular": GF + "zcoolkuaile/ZCOOLKuaiLe-Regular.ttf"},
     {"licenceUrl": GF + "zcoolkuaile/OFL.txt"}),
    ("smiley_sans", "Smiley Sans", "sfx", ["bold", "italic", "impact"], {"regular": "zip:SmileySans-Oblique.ttf"},
     {"zip": SMILEY_ZIP, "licenceUrl": "https://raw.githubusercontent.com/atelier-anchor/smiley-sans/v2.0.1/LICENSE"}),
    ("jf_open_huninn", "jf open 粉圓", "dialogue", ["rounded", "handwritten"], {"regular": HUNINN + "font/jf-openhuninn-2.1.ttf"},
     {"licenceUrl": HUNINN + "LICENSE"}),
    ("itim", "Itim", "dialogue", ["handwritten", "comic"], {"regular": GF + "itim/Itim-Regular.ttf"},
     {"licenceUrl": GF + "itim/OFL.txt"}),
    ("kanit_black", "Kanit Black", "sfx", ["bold", "impact"], {"regular": GF + "kanit/Kanit-Black.ttf"},
     {"licenceUrl": GF + "kanit/OFL.txt"}),
    ("reem_kufi_fun", "Reem Kufi Fun", "dialogue", ["rounded", "comic"], {"regular": GF + "reemkufifun/ReemKufiFun%5Bwght%5D.ttf"},
     {"licenceUrl": GF + "reemkufifun/OFL.txt", "file": "ReemKufiFun-Variable.ttf", "boldVariation": "'wght' 700"}),
    ("lalezar", "Lalezar", "sfx", ["bold", "impact"], {"regular": GF + "lalezar/Lalezar-Regular.ttf"},
     {"licenceUrl": GF + "lalezar/OFL.txt"}),
    ("kalam", "Kalam", "dialogue", ["handwritten", "comic"], {
        "regular": GF + "kalam/Kalam-Regular.ttf", "bold": GF + "kalam/Kalam-Bold.ttf"},
     {"licenceUrl": GF + "kalam/OFL.txt"}),
    ("baloo_2_extrabold", "Baloo 2 ExtraBold", "sfx", ["bold", "rounded"], {"regular": GF + "baloo2/Baloo2%5Bwght%5D.ttf"},
     {"licenceUrl": GF + "baloo2/OFL.txt", "file": "Baloo2-Variable.ttf", "variation": "'wght' 800"}),
]

# The three fonts the app already ships as resources (the reader uses them too): listed, not copied.
RESOURCE_FONTS = [
    ("coming_soon", "Coming Soon", "dialogue", ["comic", "handwritten"], "coming_soon", "Apache-2.0"),
    ("plus_jakarta_sans", "Plus Jakarta Sans", "caption", ["clean", "sans"], "plus_jakarta_sans", "OFL-1.1"),
    ("luckiest_guy", "Luckiest Guy", "sfx", ["comic", "bold", "impact"], "luckiest_guy", "Apache-2.0"),
]


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "panelglass-font-build"})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def scripts_of(path):
    cmap = TTFont(path, fontNumber=0, lazy=True).getBestCmap() or {}
    return [s for s, line in SAMPLES.items() if all(ord(c) in cmap for c in line if not c.isspace())]


def find_res(name):
    for ext in (".ttf", ".otf"):
        p = os.path.join(RES_FONTS, name + ext)
        if os.path.exists(p):
            return p
    raise SystemExit("missing resource font " + name)


def main():
    verify = "--verify" in sys.argv
    old = {}
    cat_path = os.path.join(ASSETS, "catalogue.json")
    if os.path.exists(cat_path):
        old = {f["id"]: f for f in json.load(open(cat_path, encoding="utf-8"))["fonts"]}
    zips = {}
    out = []
    for fid, name, role, tags, files, extra in FONTS:
        entry = {"id": fid, "name": name, "role": role, "tags": tags, "licence": "OFL-1.1", "files": {}, "sha256": {}}
        if extra.get("shared"):
            for style, rel in files.items():
                entry["files"][style] = rel
                entry["sha256"][style] = sha256(os.path.join(ASSETS, rel))
        else:
            d = os.path.join(ASSETS, fid)
            os.makedirs(d, exist_ok=True)
            for style, src in files.items():
                fname = extra.get("file") if style == "regular" and "file" in extra else src.split("/")[-1].replace("zip:", "")
                dst = os.path.join(d, fname)
                if not os.path.exists(dst):
                    if verify:
                        raise SystemExit("missing " + dst)
                    if src.startswith("zip:"):
                        z = zips.get(extra["zip"]) or zipfile.ZipFile(io.BytesIO(fetch(extra["zip"])))
                        zips[extra["zip"]] = z
                        member = next(m for m in z.namelist() if m.endswith("/" + src[4:]) or m == src[4:])
                        open(dst, "wb").write(z.read(member))
                    else:
                        open(dst, "wb").write(fetch(src))
                rel = fid + "/" + fname
                digest = sha256(dst)
                pinned = old.get(fid, {}).get("sha256", {}).get(style)
                if pinned and pinned != digest:
                    raise SystemExit(f"{rel}: SHA-256 changed ({pinned} -> {digest})")
                entry["files"][style] = rel
                entry["sha256"][style] = digest
            lic = os.path.join(d, "OFL.txt")
            if not os.path.exists(lic) and not verify:
                if "licenceUrl" in extra:
                    open(lic, "wb").write(fetch(extra["licenceUrl"]))
                else:
                    z = zips[extra["zip"]]
                    member = next(m for m in z.namelist() if m.endswith(extra["licenceZip"]))
                    open(lic, "wb").write(z.read(member))
        for k in ("variation", "boldVariation"):
            if k in extra:
                entry[k] = extra[k]
        entry["scripts"] = scripts_of(os.path.join(ASSETS, entry["files"]["regular"]))
        out.append(entry)
    for fid, name, role, tags, res, licence in RESOURCE_FONTS:
        out.append({"id": fid, "name": name, "role": role, "tags": tags, "licence": licence, "res": res,
                    "scripts": scripts_of(find_res(res))})
    cat = {"version": 1, "fonts": out}
    if not verify:
        json.dump(cat, open(cat_path, "w", encoding="utf-8", newline="\n"), ensure_ascii=False, indent=1)
    total = sum(os.path.getsize(os.path.join(dp, f)) for dp, _, fs in os.walk(ASSETS) for f in fs)
    for e in out:
        print(f"{e['id']:26} {e['role']:9} {','.join(e['scripts'])}")
    print(f"assets/fonts: {total / 1048576:.1f} MB")


if __name__ == "__main__":
    main()
