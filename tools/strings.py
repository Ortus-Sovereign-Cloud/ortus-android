# Makes the app's string resources from the iOS catalogs (one source of
# texts for both phones): keys with dots become underscores, %@ and %lld
# become numbered Android placeholders.
import os, re, sys
src = sys.argv[1]
langs = {"en": "values", "pl": "values-pl", "uk": "values-uk", "de": "values-de", "fr": "values-fr", "ar": "values-ar", "es": "values-es", "it": "values-it"}
pat = re.compile(r'^"(.+?)" = "(.*)";$')
for lang, folder in langs.items():
    out = ['<?xml version="1.0" encoding="utf-8"?>', "<!-- Made by tools/strings.py from ortus-ios; edit there. -->", "<resources>"]
    for line in open(os.path.join(src, f"{lang}.lproj/Localizable.strings"), encoding="utf-8"):
        m = pat.match(line.strip())
        if not m:
            continue
        key, value = m.group(1), m.group(2).replace('\\"', '"')
        key = re.sub(r"\s*%(@|lld)", "", key).replace(".", "_")
        n = 0
        def place(mm):
            global n
            n += 1
            return f"%{n}$" + ("s" if mm.group(1) == "@" else "d")
        n = 0
        value = re.sub(r"%(@|lld)", place, value)
        value = value.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'").replace('"', '\\"')
        if value.startswith("@") or value.startswith("?"):
            value = "\\" + value
        out.append(f'    <string name="{key}">{value}</string>')
    out.append("</resources>")
    os.makedirs(f"app/src/main/res/{folder}", exist_ok=True)
    open(f"app/src/main/res/{folder}/strings.xml", "w", encoding="utf-8").write("\n".join(out) + "\n")
