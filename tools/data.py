"""Apache-2.0 data preparation, release notices and verification.

Only explicitly admitted sources enter compiled packs; evaluation data is never input.
"""
from pathlib import Path
from collections import Counter
from functools import lru_cache
import argparse
import csv
import hashlib
import io
import json
import re
import shutil
import struct
import unicodedata
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]



# Reading conversion

TONE_MARKS = {"\u0304": 1, "\u0301": 2, "\u030c": 3, "\u0300": 4}


def pinyin(value):
    """A single marked syllable -> canonical ASCII with a separate tone digit."""
    tone, letters, marks = 5, [], 0
    for ch in unicodedata.normalize("NFD", value.lower()):
        if ch in TONE_MARKS:
            if not letters or letters[-1] not in set("aeiouvmn") | {"e^"}:
                raise ValueError(f"Misplaced Pinyin tone: {value!r}")
            tone = TONE_MARKS[ch]
            marks += 1
        elif ch == "\u0308" and letters and letters[-1] == "u":
            letters[-1] = "v"
        elif ch == "\u0302" and letters and letters[-1] == "e":
            letters[-1] = "e^"
        elif "a" <= ch <= "z":
            letters.append(ch)
        else:
            raise ValueError(f"Unsupported Pinyin {value!r} U+{ord(ch):04X}")
    if not letters or marks > 1:
        raise ValueError(f"Not one Pinyin syllable: {value!r}")
    return "".join(letters) + str(tone)


INITIALS = dict(zip("ㄅㄆㄇㄈㄉㄊㄋㄌㄍㄎㄏㄐㄑㄒㄓㄔㄕㄖㄗㄘㄙ",
                    "b p m f d t n l g k h j q x zh ch sh r z c s".split()))
FINALS = {
    "ㄚ": "a", "ㄛ": "o", "ㄜ": "e", "ㄝ": "e^", "ㄞ": "ai", "ㄟ": "ei",
    "ㄠ": "ao", "ㄡ": "ou", "ㄢ": "an", "ㄣ": "en", "ㄤ": "ang", "ㄥ": "eng", "ㄦ": "er",
    "ㄧ": "i", "ㄧㄚ": "ia", "ㄧㄛ": "io", "ㄧㄝ": "ie", "ㄧㄞ": "iai", "ㄧㄠ": "iao",
    "ㄧㄡ": "iu", "ㄧㄢ": "ian", "ㄧㄣ": "in", "ㄧㄤ": "iang", "ㄧㄥ": "ing",
    "ㄨ": "u", "ㄨㄚ": "ua", "ㄨㄛ": "uo", "ㄨㄞ": "uai", "ㄨㄟ": "ui",
    "ㄨㄢ": "uan", "ㄨㄣ": "un", "ㄨㄤ": "uang", "ㄨㄥ": "ong",
    "ㄩ": "v", "ㄩㄝ": "ve", "ㄩㄢ": "van", "ㄩㄣ": "vn", "ㄩㄥ": "iong",
}
ZERO_INITIAL = {
    "ㄧ": "yi", "ㄧㄚ": "ya", "ㄧㄛ": "yo", "ㄧㄝ": "ye", "ㄧㄞ": "yai", "ㄧㄠ": "yao",
    "ㄧㄡ": "you", "ㄧㄢ": "yan", "ㄧㄣ": "yin", "ㄧㄤ": "yang", "ㄧㄥ": "ying",
    "ㄨ": "wu", "ㄨㄚ": "wa", "ㄨㄛ": "wo", "ㄨㄞ": "wai", "ㄨㄟ": "wei",
    "ㄨㄢ": "wan", "ㄨㄣ": "wen", "ㄨㄤ": "wang", "ㄨㄥ": "weng",
    "ㄩ": "yu", "ㄩㄝ": "yue", "ㄩㄢ": "yuan", "ㄩㄣ": "yun", "ㄩㄥ": "yong",
}
BPMF_TONES = {"ˊ": 2, "ˇ": 3, "ˋ": 4, "˙": 5}


@lru_cache(maxsize=4096)
def bopomofo(value):
    """Single source syllable. An omitted Bopomofo tone means first tone."""
    tone = 1
    marks = [i for i, ch in enumerate(value) if ch in BPMF_TONES]
    if len(marks) > 1 or (marks and marks[0] not in (0, len(value) - 1)):
        raise ValueError(f"Invalid Bopomofo tone: {value!r}")
    if marks:
        mark = value[marks[0]]
        if marks[0] == 0 and mark != "˙":
            raise ValueError(f"Invalid initial tone: {value!r}")
        tone = BPMF_TONES[mark]
        value = value.replace(mark, "")
    if not value:
        raise ValueError("Empty Bopomofo syllable")
    initial = INITIALS.get(value[0], "")
    final = value[1:] if initial else value
    if not final:
        if initial not in {"zh", "ch", "sh", "r", "z", "c", "s", "m", "n"}:
            raise ValueError(f"Missing Bopomofo final: {value!r}")
        spelling = initial + ("i" if initial not in {"m", "n"} else "")
    elif final not in FINALS:
        raise ValueError(f"Unsupported Bopomofo final: {value!r}")
    elif not initial:
        spelling = ZERO_INITIAL.get(final, FINALS[final])
    else:
        ending = FINALS[final]
        if initial in {"j", "q", "x"} and final.startswith("ㄩ"):
            ending = ending.replace("v", "u")
        # Pinyin writes lüe/nüe, not the ASCII phonetic form lve/nve.
        spelling = initial + ending
    return spelling + str(tone)


def is_han_surface(surface):
    return bool(surface) and all(0x3400 <= ord(ch) <= 0x9FFF or 0xF900 <= ord(ch) <= 0xFAFF
                                or 0x20000 <= ord(ch) <= 0x33479 for ch in surface)


def dictionary_tones(surface, syllables):
    """Remove explicit 一/不 sandhi, without guessing other tones or neutral tones."""
    if len(surface) != len(syllables):
        raise ValueError("Unaligned reading")
    return tuple("yi1" if ch == "一" and sound in {"yi2", "yi4"}
                 else "bu4" if ch == "不" and sound == "bu2" else sound
                 for ch, sound in zip(surface, syllables))


def aligned_pinyin(surface, reading, inventory, character_bases):
    """Split joined Pinyin, respecting separators and Unicode scalar alignment.

    Enumerate up to two solutions per state. If syllable spelling alone is
    ambiguous, Unihan's character bases may prove a unique alignment. Otherwise
    reject it; never choose an arbitrary longest syllable or invent a tone.
    """
    separators = " '-"
    text = unicodedata.normalize("NFC", reading.lower())
    if not is_han_surface(surface) or len(surface) > 256:
        raise ValueError("Non-Han or excessive surface")
    for ch in text:
        if ch not in separators and not ("a" <= ch <= "z" or ch in "āáǎàēéěèīíǐìōóǒòūúǔùüǖǘǚǜêńňǹḿ\u0301"):
            raise ValueError("Unsupported reading notation")

    def solutions(use_character_bases):
        @lru_cache(maxsize=None)
        def visit(index, offset):
            while offset < len(text) and text[offset] in separators:
                offset += 1
            if index == len(surface):
                return ((),) if offset == len(text) else ()
            candidates = []
            for end in range(offset + 1, min(offset + 9, len(text) + 1)):
                if text[end - 1] in separators:
                    break
                try:
                    syllable = pinyin(text[offset:end])
                except ValueError:
                    continue
                base = syllable[:-1]
                if base not in inventory:
                    continue
                known = character_bases.get(surface[index])
                if use_character_bases and known and base not in known:
                    continue
                for tail in visit(index + 1, end):
                    candidates.append((syllable,) + tail)
                    if len(candidates) == 2:
                        return tuple(candidates)
            return tuple(candidates)
        return visit(0, 0)

    found = solutions(False)
    if len(found) > 1:
        found = solutions(True)
    if len(found) != 1:
        raise ValueError("No unique syllable alignment")
    return found[0]


# Owned source admission

ADMITTED = {
    "common-polyphones.tsv": "accompanist-common-polyphones",
}


def load_curated(folder):
    manifest = json.loads((folder / "sources.json").read_text("utf-8"))
    if set(manifest) != set(ADMITTED):
        raise ValueError("Owned source admission changed; review required")
    if {path.name for path in folder.glob("*.tsv")} != set(ADMITTED):
        raise ValueError("Undeclared owned dictionary file")
    entries = []
    for filename, source_id in ADMITTED.items():
        record = manifest[filename]
        if (record["source_id"] != source_id or record["role"] != "redistribute"
                or record["license"] != "Apache-2.0" or not record["author"]):
            raise ValueError(f"Invalid owned source declaration: {filename}")
        path = folder / filename
        if hashlib.sha256(path.read_bytes()).hexdigest() != record["sha256"]:
            raise ValueError(f"Owned source hash changed: {filename}")
        counts = Counter()
        for line in path.read_text("utf-8").splitlines():
            if not line or line.startswith("#"):
                continue
            locale, surface, reading, cost, aligned = line.split("\t")
            if locale not in {"zh-CN", "zh-TW", "ja-JP"}:
                raise ValueError(f"Unreviewed owned source locale: {locale}")
            if locale == "ja-JP" and (aligned != "0" or not re.fullmatch("[ぁ-ゖー]+", reading)):
                raise ValueError("Japanese owned entries require kana and indivisible word alignment")
            counts[locale] += 1
            entries.append((locale, surface, reading, int(cost), int(aligned), source_id))
        if dict(counts) != record["entries"]:
            raise ValueError(f"Owned source scope/count changed: {filename}")
    return entries


# Release layout

LICENSE_PATHS = {
    "Unicode-LICENSE.txt": "unicode/LICENSE",
    "CedPane-LICENSE.txt": "cedpane/LICENSE",
    "CedPane-README.md": "cedpane/README.md",
    "McBopomofo-LICENSE.txt": "mcbopomofo/LICENSE",
    "McBopomofo-Data-README.md": "mcbopomofo/DATA-README.md",
    "libtabe-tsi-COPYING.txt": "libtabe/COPYING",
    "Rime-CC-BY-4.0.txt": "rime-cantonese/LICENSE",
    "Rime-README.md": "rime-cantonese/README.md",
    "IPADIC-COPYING.txt": "ipadic/COPYING",
}
MODULE_LICENSES = {
    "data-mandarin": ["Unicode-LICENSE.txt", "CedPane-LICENSE.txt", "CedPane-README.md",
                      "McBopomofo-LICENSE.txt", "McBopomofo-Data-README.md", "libtabe-tsi-COPYING.txt"],
    "data-cantonese": ["Unicode-LICENSE.txt", "Rime-CC-BY-4.0.txt", "Rime-README.md"],
    "data-japanese": ["IPADIC-COPYING.txt"],
}
MODULE_INPUTS = {
    "data-mandarin": ["Unihan-17.0.0.zip", "CedPane.tsv", "McBopomofo-BPMFMappings.txt"],
    "data-cantonese": ["Unihan-17.0.0.zip", "rime-cantonese.zip"],
    "data-japanese": ["mecab.zip"],
}


def notice_files(module):
    return ["LICENSE", "NOTICE", "sources.json"] + [LICENSE_PATHS[name] for name in MODULE_LICENSES[module]]


def source_manifest(root, module):
    lock = json.loads((root / "data/sources.lock.json").read_text("utf-8"))
    names = MODULE_INPUTS[module] + [name for name in MODULE_LICENSES[module] if name in lock]
    licenses = {}
    for name in MODULE_LICENSES[module]:
        path = LICENSE_PATHS[name]
        licenses[path] = {"sha256": hashlib.sha256((root / "data/licenses" / path).read_bytes()).hexdigest()}
    packs = []
    for line in (root / "data/compiled-packs.tsv").read_text("utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        tag, surfaces, variants, size, digest = line.split("\t")
        if (root / module / "packs" / (tag + ".lpd")).is_file():
            packs.append(dict(file=tag + ".lpd", surfaces=int(surfaces), variants=int(variants), bytes=int(size), sha256=digest))
    locales = {p["file"].removesuffix(".lpd") for p in packs}
    owned = {name: record for name, record in json.loads((root / "data/curated/sources.json").read_text("utf-8")).items()
             if locales.intersection(record["entries"])}
    manifest = dict(schema=1, module=module, upstream={name: lock[name] for name in names},
                    owned=owned, licenses=licenses, packs=packs)
    if module == "data-japanese":
        path = "data-japanese/src/commonMain/kotlin/com/mocharealm/accompanist/lyrics/phonetics/data/IpadicLexicon.kt"
        manifest["derived_metadata"] = dict(path=path, sha256=hashlib.sha256((root / path).read_bytes()).hexdigest(),
            source="ipadic", license="LicenseRef-NAIST-ICOT", license_path="ipadic/COPYING",
            changes="Compact context-ID POS categories and attributive-inflection bitmap derived from the pinned IPADIC CSVs; no parser code or validation data imported.")
    return manifest


def prepare():
    RAW = ROOT / "data/sources"
    OUT = ROOT / "data/prepared"
    NOTICES = ROOT / "data/licenses"
    LOCK = ROOT / "data/sources.lock.json"
    MECAB = "61b90ba6e669dc2d7d533d4a80d206f3b31d52b1"
    RIME = "259f0e48bba840c3a2e0d117539e96937f3d89bc"
    CEDPANE = "bcc2c145da6fa0e03191f0d49ea193f28d924061"
    MCBOPOMOFO = "be6564acad6c4d3265c34a2e1a872d80f9db6068"
    LIBTABE = "ea382a829c916f2cceda2022afdf8e8471e6e7b5"
    for p in (RAW, OUT, NOTICES, ROOT / "benchmark/results"):
        p.mkdir(parents=True, exist_ok=True)
    for path in LICENSE_PATHS.values():
        (NOTICES / path).parent.mkdir(parents=True, exist_ok=True)
    locked = json.loads(LOCK.read_text("utf-8")) if LOCK.exists() else {}
    acquired = {}

    def acquire(name, url, license_id, role="redistribute"):
        path = RAW / name
        if not path.exists():
            print("Downloading", name, flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "lyrics-phonetics-data-compiler/0.1"})
            with urllib.request.urlopen(req, timeout=120) as response: path.write_bytes(response.read())
        payload = path.read_bytes()
        record = dict(url=url, sha256=hashlib.sha256(payload).hexdigest(), license=license_id, role=role)
        if name in locked and locked[name] != record: raise ValueError(f"Source lock mismatch: {name}")
        acquired[name] = record
        return payload

    unihan = zipfile.ZipFile(io.BytesIO(acquire("Unihan-17.0.0.zip", "https://www.unicode.org/Public/17.0.0/ucd/Unihan.zip", "Unicode-3.0")))
    unicode_license = acquire("Unicode-LICENSE.txt", "https://www.unicode.org/license.txt", "Unicode-3.0")
    (NOTICES / "unicode/LICENSE").write_bytes(unicode_license)
    rime = zipfile.ZipFile(io.BytesIO(acquire("rime-cantonese.zip", f"https://codeload.github.com/rime/rime-cantonese/zip/{RIME}", "CC-BY-4.0")))
    ipadic = zipfile.ZipFile(io.BytesIO(acquire("mecab.zip", f"https://codeload.github.com/taku910/mecab/zip/{MECAB}", "LicenseRef-NAIST-ICOT")))
    cedpane = acquire("CedPane.tsv", f"https://raw.githubusercontent.com/ssb22/CedPane/{CEDPANE}/cedpane.tsv", "LicenseRef-CedPane-PublicDomain")
    mcbopomofo = acquire("McBopomofo-BPMFMappings.txt", f"https://raw.githubusercontent.com/openvanilla/McBopomofo/{MCBOPOMOFO}/Source/Data/BPMFMappings.txt", "MIT AND BSD-3-Clause")
    for name, url, license_id in (
        ("CedPane-LICENSE.txt", f"https://raw.githubusercontent.com/ssb22/CedPane/{CEDPANE}/LICENSE", "Unlicense"),
        ("CedPane-README.md", f"https://raw.githubusercontent.com/ssb22/CedPane/{CEDPANE}/README.md", "LicenseRef-CedPane-Readme"),
        ("McBopomofo-LICENSE.txt", f"https://raw.githubusercontent.com/openvanilla/McBopomofo/{MCBOPOMOFO}/LICENSE.txt", "MIT"),
        ("McBopomofo-Data-README.md", f"https://raw.githubusercontent.com/openvanilla/McBopomofo/{MCBOPOMOFO}/Source/Data/README.md", "MIT"),
        ("libtabe-tsi-COPYING.txt", f"https://raw.githubusercontent.com/kcwu/libtabe/{LIBTABE}/tsi-src/COPYING", "BSD-3-Clause"),
    ):
        (NOTICES / LICENSE_PATHS[name]).write_bytes(acquire(name, url, license_id))

    def member(archive, suffix):
        names = [n for n in archive.namelist() if n.endswith("/" + suffix)]
        if len(names) != 1: raise ValueError(f"Expected exactly one {suffix}: {names}")
        return archive.read(names[0])

    (NOTICES / "rime-cantonese/LICENSE").write_bytes(member(rime, "LICENSE-CC-BY"))
    (NOTICES / "ipadic/COPYING").write_bytes(member(ipadic, "mecab-ipadic/COPYING"))
    (NOTICES / "rime-cantonese/README.md").write_bytes(member(rime, "README.md"))

    rows = {"zh-CN": [], "zh-TW": [], "yue-HK": [], "ja-JP": []}

    def add(locale, surface, reading, cost, left=0, right=0, aligned=1, source="unihan-17"):
        if not surface or not reading or any(c in surface + reading for c in "\t\n\r"): raise ValueError("Invalid entry")
        rows[locale].append((surface, reading, cost, left, right, aligned, source))

    for line in unihan.read("Unihan_Readings.txt").decode("utf-8").splitlines():
        if not line or line.startswith("#"): continue
        code, field, value = line.split("\t")
        surface = chr(int(code[2:], 16))
        if field == "kMandarin":
            readings = value.split()
            add("zh-CN", surface, pinyin(readings[0]), 900)
            add("zh-TW", surface, pinyin(readings[-1]), 900)
        elif field == "kHanyuPinyin":
            for r in sorted({r for group in value.split() for r in group.split(":")[1].split(",")}):
                for locale in ("zh-CN", "zh-TW"): add(locale, surface, pinyin(r), 1000)
        elif field == "kCantonese":
            for reading in value.split(): add("yue-HK", surface, reading.lower(), 900)

    # Admit only these named dictionary files, not upstream frequency corpora,
    # validation material, Cantonese Yale columns, or English-only gloss tables.
    character_bases = {}
    for locale in ("zh-CN", "zh-TW"):
        for surface, reading, *_ in rows[locale]:
            character_bases.setdefault(surface, set()).add(reading[:-1])
    inventory = frozenset(base for bases in character_bases.values() for base in bases)
    normalization = {}

    def normalize_source(name, records, convert, locale, source, cost):
        rejected, examples = Counter(), []
        before = len(rows[locale])
        changes = 0
        total = 0
        for surface, raw_reading in records:
            total += 1
            try:
                if not is_han_surface(surface): raise ValueError("Non-Han surface")
                syllables = convert(surface, raw_reading)
                canonical = dictionary_tones(surface, syllables)
                changes += canonical != tuple(syllables)
                add(locale, surface, " ".join(canonical), cost, source=source)
            except ValueError as error:
                rejected[str(error)] += 1
                if len(examples) < 12:
                    examples.append(dict(surface=surface, reading=raw_reading, reason=str(error)))
        selected = [row for row in rows[locale][before:]]
        normalization[name] = dict(locale=locale, source_id=source, input_records=total,
                                   admitted_records=len(selected), unique_surface_readings=len(set((r[0], r[1]) for r in selected)),
                                   distinct_surfaces=len(set(r[0] for r in selected)), explicit_sandhi_records_normalized=changes,
                                   rejected_records=sum(rejected.values()), rejection_reasons=dict(sorted(rejected.items())),
                                   rejection_examples=examples)
        print(name, normalization[name]["admitted_records"], "admitted;", sum(rejected.values()), "filtered", flush=True)

    cp_records = csv.DictReader(io.StringIO(cedpane.decode("utf-8")), delimiter="\t")
    normalize_source("cedpane", ((r["Simplified"], r["Pinyin"]) for r in cp_records),
                     lambda surface, reading: aligned_pinyin(surface, reading, inventory, character_bases),
                     "zh-CN", "cedpane", 400)

    def mc_reading(surface, reading):
        sounds = tuple(bopomofo(value) for value in reading.split())
        if len(surface) != len(sounds): raise ValueError("Unaligned reading")
        return sounds

    mc_records = (line.split(maxsplit=1) for line in mcbopomofo.decode("utf-8").splitlines()
                  if line.strip() and not line.startswith("#"))
    normalize_source("mcbopomofo", ((r[0], r[1]) for r in mc_records), mc_reading,
                     "zh-TW", "mcbopomofo", 200)

    for file in ("jyut6ping3.chars.dict.yaml", "jyut6ping3.words.dict.yaml"):
        active = False
        for line in member(rime, file).decode("utf-8").splitlines():
            if line == "...": active = True; continue
            if not active or not line or line.startswith("#"): continue
            parts = line.split("\t")
            if len(parts) < 2: continue
            surface, reading = parts[:2]
            if not re.fullmatch(r"[a-z]+[1-6]( [a-z]+[1-6])*", reading): continue
            # Ignore input-method weights: they are not calibrated language-model probabilities.
            if len(surface) != len(reading.split()): continue
            add("yue-HK", surface, reading, 900 if len(surface) == 1 else 200, source="rime-cantonese")

    owned_entries = load_curated(ROOT / "data/curated")
    calendar_prefixes = {surface for locale, surface, *_ in owned_entries if locale == "ja-JP"}
    preferred_japanese = set()
    for name in sorted(ipadic.namelist()):
        if "/mecab-ipadic/" not in name or not name.endswith(".csv"): continue
        content = ipadic.read(name).decode("euc_jp")
        for fields in csv.reader(io.StringIO(content)):
            if len(fields) < 13 or fields[11] == "*": continue
            surface, left, right, cost = fields[:4]
            # Readings, not speech pronunciations: particles and long vowels stay separate from formatting.
            kana = fields[12] if fields[4] == "助詞" else fields[11]
            reading = "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" else c for c in kana)
            if not all("ぁ" <= c <= "ゖ" or c in "ーゔゕゖ" for c in reading): continue
            # Reviewed cost corrections, not corpus-derived frequencies. Keep every
            # upstream reading/POS variant, including the personal name Kazuto.
            ordinary_noun = fields[4] == "名詞" and fields[5] in {"一般", "サ変接続"}
            person_word = ((surface, reading) in {("一人", "ひとり"), ("二人", "ふたり")} or
                           len(surface) > 2 and surface.startswith(("一人", "二人")))
            calendar_compound = (fields[4:6] != ["名詞", "固有名詞"] and
                                 any(len(surface) > len(prefix) and surface.startswith(prefix) for prefix in calendar_prefixes))
            if ordinary_noun and person_word or calendar_compound:
                preferred_japanese.add((surface, int(left), int(right)))
            add("ja-JP", surface, reading, int(cost), int(left), int(right), 0, "ipadic")
    # Shift whole reading/POS families together. Clamping individual variants
    # would erase upstream ordering (e.g. samidare versus satsukiame).
    minimum_costs = {}
    for surface, reading, cost, left, right, aligned, source in rows["ja-JP"]:
        key = (surface, left, right)
        if key in preferred_japanese:
            minimum_costs[key] = min(cost, minimum_costs.get(key, cost))
    rows["ja-JP"] = [(surface, reading, cost - max(0, minimum_costs.get((surface, left, right), 1000) - 1000),
                      left, right, aligned, source)
                     for surface, reading, cost, left, right, aligned, source in rows["ja-JP"]]
    matrix = member(ipadic, "mecab-ipadic/matrix.def")
    (OUT / "ja-JP.matrix").write_bytes(matrix)

    # Only these original, reviewed additions are admitted. No test corpus is read here.
    for locale, surface, reading, cost, aligned, source in owned_entries:
        # These reviewed Japanese additions are nominal calendar expressions.
        # IPADIC's ordinary noun connection IDs remain tied to the pinned matrix.
        context = 1285 if locale == "ja-JP" else 0
        add(locale, surface, reading, cost, left=context, right=context, aligned=aligned, source=source)

    manifest = []
    for locale, entries in rows.items():
        path = OUT / (locale + ".tsv")
        with path.open("w", encoding="utf-8", newline="\n") as out:
            for row in sorted(set(entries)): out.write("\t".join(map(str, row)) + "\n")
        manifest.append(f"{locale}\t{path.relative_to(ROOT).as_posix()}\t{hashlib.sha256(path.read_bytes()).hexdigest()}\tredistribute")
        print(locale, len(entries), "normalized records", flush=True)
    manifest.append(f"ja-matrix\tdata/prepared/ja-JP.matrix\t{hashlib.sha256(matrix).hexdigest()}\tredistribute")
    if locked and acquired.keys() != locked.keys(): raise ValueError("Source lock set changed; review required")
    LOCK.write_text(json.dumps(acquired, ensure_ascii=False, indent=2) + "\n", "utf-8")
    (ROOT / "data/release-inputs.tsv").write_text("\n".join(manifest) + "\n", "utf-8")
    (ROOT / "benchmark/results/data-normalization.json").write_text(json.dumps(normalization, ensure_ascii=False, indent=2) + "\n", "utf-8")


def notices():
    code_notices = ROOT / "runtime/build/generated/notices/META-INF"
    code_notices.mkdir(parents=True, exist_ok=True)
    for filename in ("LICENSE", "NOTICE"): shutil.copyfile(ROOT / filename, code_notices / filename)
    for module, licenses in MODULE_LICENSES.items():
        name = module.removeprefix("data-")
        folder = ROOT / module / "build/generated/notices/phonetics-notices" / module
        # This directory contains only generated release notices. Validate its location
        # before removing the old layout so obsolete research/documents cannot survive.
        assert folder.resolve() == ROOT.resolve() / module / "build/generated/notices/phonetics-notices" / module
        assert not folder.is_symlink()
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir(parents=True)
        for filename in licenses:
            relative = LICENSE_PATHS[filename]
            target = folder / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / "data/licenses" / relative, target)
        shutil.copyfile(ROOT / "LICENSE", folder / "LICENSE")
        (folder / "sources.json").write_text(json.dumps(source_manifest(ROOT, module), ensure_ascii=False, indent=2) + "\n", "utf-8")
        notice = (ROOT / "NOTICE").read_text("utf-8") + "\n"
        if name == "cantonese":
            notice += ("Rime Cantonese: developed and maintained by the Cantonese Computational\n"
                       "Linguistics Infrastructure Development Workgroup (CanCLID).\n"
                       "Source: https://github.com/rime/rime-cantonese\n"
                       "Data: CC-BY-4.0; original disclaimers and attribution retained.\n"
                       "Changes: extracted only chars/words readings; filtered unaligned entries;\n"
                       "normalized, deduplicated, prefix-compressed and indexed. No .maps imported.\n")
        if name in ("mandarin", "cantonese"):
            notice += "Unicode Unihan 17.0.0: extracted selected reading fields; tone conversion, deduplication, compression and indexing.\n"
        if name == "mandarin":
            notice += "Accompanist original common-polyphone/context additions: Copyright 2026 Accompanist contributors, Apache-2.0; source declaration and hashes are in sources.json.\n"
            notice += ("CedPane: Silas S. Brown. Source: https://ssb22.user.srcf.net/cedpane/\n"
                       "Data is dedicated to the public domain by the author; repository LICENSE\n"
                       "is Unlicense and dataset metadata states pddl. All original declarations\n"
                       "are retained. The README is copyright Silas S. Brown, reproduced as-is\n"
                       "under its separate express permission, not relicensed.\n"
                       "Changes: extracted only Simplified/Pinyin from cedpane.tsv into zh-CN;\n"
                       "split joined Pinyin with verified scalar alignment; filtered unsupported\n"
                       "notations/ambiguous alignment; restored explicit yi/bu sandhi to dictionary\n"
                       "tones; deduplicated, compressed and indexed. No gloss or Yale data imported.\n"
                       "McBopomofo: Copyright (c) 2011-2026 Mengjuei Hsieh et al., MIT.\n"
                       "Source: https://github.com/openvanilla/McBopomofo\n"
                       "BPMFMappings.txt derives from libtabe tsi.src (BSD-3-Clause).\n"
                       "Copyright (c) 1999 TaBE Project and Pai-Hsiang Hsiao.\n"
                       "Copyright (c) 1999 Computer Systems and Communication Lab,\n"
                       "Institute of Information Science, Academia Sinica.\n"
                       "Copyright 1996 Chih-Hao Tsai, Beckman Institute, University of Illinois.\n"
                       "The entire upstream libtabe tsi-src/COPYING is included.\n"
                       "Changes: extracted only BPMFMappings.txt into zh-TW; converted Bopomofo\n"
                       "with original rules, retained other readings/neutral tones, restored explicit\n"
                       "yi/bu sandhi to dictionary tones; filtered unsupported/unaligned entries;\n"
                       "deduplicated, compressed and indexed. No upstream frequency corpus,\n"
                       "resolver code or validation-only material imported.\n")
        if name == "japanese":
            notice += ("IPADIC: Copyright 2000, 2001, 2002, 2003 Nara Institute of Science and Technology.\n"
                       "A large portion of dictionary entries originates from ICOT Free Software.\n"
                       "Changes: extracted surfaces, readings, word/context costs; normalized kana,\n"
                       "deduplicated, prefix-compressed and indexed. Full COPYING is included.\n"
                       "Compact POS/context-ID ranges and attributive-inflection bitmap in\n"
                       "IpadicLexicon.kt are derived from the same pinned CSV metadata and\n"
                       "remain covered by the included NAIST/ICOT license and attribution.\n"
                       "Cost corrections: ordinary-noun hitori/futari and existing compounds\n"
                       "beginning with 一人/二人 use cost 1000, preserving compound readings\n"
                       "such as ichininmae/ichininshou/nininsankyaku;\n"
                       "Existing non-proper-name calendar-prefix compounds (e.g. samidare,\n"
                       "mikazuki) also receive a whole-word cost preference. Each reading/POS\n"
                       "family is shifted together toward minimum cost 1000, retaining all\n"
                       "upstream differences between alternative readings.\n"
                       "other readings/POS variants remain unchanged. These are original\n"
                       "selection weights, not corpus-derived frequencies.\n"
                       "Accompanist original calendar additions: Copyright 2026 Accompanist\n"
                       "contributors, Apache-2.0; indivisible nominal readings, ordinary-noun\n"
                       "connection IDs 1285, source declaration and hashes in sources.json.\n")
        (folder / "NOTICE").write_text(notice, "utf-8")


def verify(apk_path=None, maven_path=None, version=None):
    records = {}
    for line in (ROOT / "data/compiled-packs.tsv").read_text("utf-8").splitlines():
        if line.startswith("#") or not line: continue
        locale, surfaces, variants, length, digest = line.split("\t")
        records[locale + ".lpd"] = (int(length), digest)
        module = "data-mandarin" if locale.startswith("zh") else "data-cantonese" if locale.startswith("yue") else "data-japanese"
        payload = (ROOT / module / "packs" / (locale + ".lpd")).read_bytes()
        assert len(payload) == int(length) and hashlib.sha256(payload).hexdigest() == digest, locale
        header = struct.unpack("<16i", payload[:64])
        assert header[0:2] == (0x3144504C, 1) and header[3:5] == (int(surfaces), int(variants)) and header[14] == len(payload), locale
    license_files = {module: notice_files(module) for module in MODULE_LICENSES}
    load_curated(ROOT / "data/curated")
    lock = json.loads((ROOT / "data/sources.lock.json").read_text("utf-8"))
    assert all(item["role"] == "redistribute" and item["license"] in {
        "Unicode-3.0", "CC-BY-4.0", "LicenseRef-NAIST-ICOT", "LicenseRef-CedPane-PublicDomain",
        "LicenseRef-CedPane-Readme", "Unlicense", "MIT AND BSD-3-Clause", "MIT", "BSD-3-Clause"
    } for item in lock.values())
    for original, relative in LICENSE_PATHS.items():
        if original in lock:
            assert hashlib.sha256((ROOT / "data/licenses" / relative).read_bytes()).hexdigest() == lock[original]["sha256"], original
    for module, filenames in license_files.items():
        folder = ROOT / module / "build/generated/notices/phonetics-notices" / module
        actual = {path.relative_to(folder).as_posix() for path in folder.rglob("*") if path.is_file()}
        assert actual == set(filenames), f"Missing or obsolete release notices: {module}: {actual ^ set(filenames)}"
        assert (folder / "LICENSE").read_bytes() == (ROOT / "LICENSE").read_bytes(), module
        assert json.loads((folder / "sources.json").read_text("utf-8")) == source_manifest(ROOT, module), module
        for original in MODULE_LICENSES[module]:
            relative = LICENSE_PATHS[original]
            assert (folder / relative).read_bytes() == (ROOT / "data/licenses" / relative).read_bytes(), f"Altered license: {module}/{relative}"


    def verify_archive_notices(archive, prefix, module):
        base = prefix + "phonetics-notices/" + module + "/"
        actual = {name[len(base):] for name in archive.namelist() if name.startswith(base) and not name.endswith("/")}
        assert actual == set(license_files[module]), f"Missing or obsolete archived notices: {module}: {actual ^ set(license_files[module])}"
        for name in license_files[module]:
            assert archive.read(base + name) == (ROOT / module / "build/generated/notices/phonetics-notices" / module / name).read_bytes(), name

    if apk_path:
        with zipfile.ZipFile(apk_path) as apk:
            for name, (size, digest) in records.items():
                info = apk.getinfo("assets/" + name)
                assert info.compress_type == zipfile.ZIP_STORED, f"Must be mmap-able: {name}"
                assert info.file_size == size and hashlib.sha256(apk.read(info)).hexdigest() == digest, name
            for module in license_files:
                verify_archive_notices(apk, "assets/", module)
    if maven_path:
        VERSION = version or re.search(r'getOrElse\("([0-9][^"]*)"\)', (ROOT / "build.gradle.kts").read_text("utf-8")).group(1)
        repository = maven_path / "com/mocharealm/accompanist"
        for module in ["runtime", *license_files]:
            base = "lyrics-phonetics-" + module
            for target in ("jvm", "android", "iosarm64", "iossimulatorarm64"):
                coordinate = base + "-" + target
                folder = repository / coordinate / VERSION
                extension = "jar" if target == "jvm" else "aar" if target == "android" else "klib"
                artifact = folder / (coordinate + "-" + VERSION + "." + extension)
                with zipfile.ZipFile(artifact) as archive:
                    prefix = "assets/" if target == "android" else "default/resources/" if target.startswith("ios") else ""
                    if module == "runtime":
                        for name in ("LICENSE", "NOTICE"):
                            assert archive.read(prefix + "META-INF/" + name) == (ROOT / name).read_bytes(), artifact
                    else:
                        verify_archive_notices(archive, prefix, module)
                        if target in ("jvm", "android"):
                            for pack in (ROOT / module / "packs").glob("*.lpd"):
                                assert archive.read(prefix + pack.name) == pack.read_bytes(), artifact
                if module != "runtime" and target.startswith("ios"):
                    with zipfile.ZipFile(folder / (coordinate + "-" + VERSION + "-resources.zip")) as archive:
                        for pack in (ROOT / module / "packs").glob("*.lpd"): assert archive.read(pack.name) == pack.read_bytes()
                        verify_archive_notices(archive, "", module)
            metadata_file = repository / base / VERSION / (base + "-" + VERSION + ".module")
            metadata = json.loads(metadata_file.read_text("utf-8"))
            if module != "runtime":
                # The sample obtains test data from this root resources publication.
                with zipfile.ZipFile(metadata_file.parent / (base + "-" + VERSION + "-resources.zip")) as archive:
                    for pack in (ROOT / module / "packs").glob("*.lpd"):
                        assert archive.read(pack.name) == pack.read_bytes(), pack
                    verify_archive_notices(archive, "", module)
            for variant in metadata["variants"]:
                if "available-at" in variant:
                    assert (metadata_file.parent / variant["available-at"]["url"]).resolve().is_file(), variant
        print("All 16 platform publications, exact license copies, native resources and metadata links verified.")
    print("Release data, licenses, provenance and optional APK assets verified.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("prepare", help="Normalize the pinned, admitted source data")
    commands.add_parser("notices", help="Generate only applicable release license materials")
    verify_parser = commands.add_parser("verify", help="Check source hashes, compiled packs and release materials")
    verify_parser.add_argument("--apk", type=Path)
    verify_parser.add_argument("--maven", type=Path)
    verify_parser.add_argument("--version", help="Published version when verifying a Maven repository")
    args = parser.parse_args()
    if args.command == "prepare":
        prepare()
    elif args.command == "notices":
        notices()
    else:
        verify(args.apk, args.maven, args.version)


if __name__ == "__main__":
    main()
