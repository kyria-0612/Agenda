"""Convert the project's OFL WOFF assets into Android-supported TTF, without downloading fonts."""
from pathlib import Path
from shutil import copy2
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib.instancer import instantiateVariableFont
import re

root = Path(__file__).resolve().parents[2]
source = root / "prototype/visual-directions/assets/fonts"
target = root / "android/app/src/main/res/font"
licenses = root / "android/app/src/main/assets/font-licenses"
target.mkdir(parents=True, exist_ok=True)
licenses.mkdir(parents=True, exist_ok=True)
for name in ("notosanssc", "notoserifsc", "manrope", "spacegrotesk"):
    full_source = root / "android/.font-sources" / f"{name}.ttf"
    font = TTFont(full_source if full_source.exists() else source / f"{name}.woff")
    if full_source.exists():
        existing = set(TTFont(source / f"{name}.woff").getBestCmap())
        code = "".join(p.read_text(encoding="utf-8")
                       for module in ("app", "domain", "data")
                       for p in (root / "android" / module / "src/main/kotlin").rglob("*.kt"))
        existing.update(map(ord, re.findall(r"[\u4e00-\u9fff]", code)))
        options = subset.Options()
        options.layout_features = ["*"]
        subsetter = subset.Subsetter(options=options)
        subsetter.populate(unicodes=existing)
        subsetter.subset(font)
    if "fvar" in font:
        # Android's resource declaration alone does not select a variable font's wght axis.
        # Pin the intended 400 instead of shipping Noto's 100 / Manrope's 200 defaults.
        instantiateVariableFont(font, {"wght": 400}, inplace=True)
    font.flavor = None
    font.save(target / f"{name}.ttf")
    copy2(source / f"{name}-OFL.txt", licenses / f"{name}-OFL.txt")
brand = TTFont(source / "spacegrotesk.woff")
instantiateVariableFont(brand, {"wght": 600}, inplace=True)
brand.flavor = None
brand.save(target / "spacegrotesk_semibold.ttf")
copy2(source / "sources.json", licenses / "sources.json")
print("Converted 4 local font families plus brand weight 600; OFL licenses included.")
