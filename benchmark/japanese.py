"""Reproduce the local-only Japanese corpus evaluation; never prepare release data."""
from pathlib import Path
from collections import Counter, defaultdict
import argparse
import hashlib
import heapq
import json
import os
import re
import subprocess
import unicodedata
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "benchmark/build/japanese-validation"
SEED = "Accompanist-ja-v1:"
SOURCES = {
    "kwdlc": {
        "url": "https://codeload.github.com/ku-nlp/KWDLC/zip/c6ae49d29eca4e1134c6676d682adf5ed6a25f5a",
        "sha256": "96c500d57ea8f079fd45241c8be3d6bdb1535188d119c63c28c630bdf95cf066",
        "terms": "https://github.com/ku-nlp/KWDLC/blob/c6ae49d29eca4e1134c6676d682adf5ed6a25f5a/README.md#notes",
        "use": "Publisher's research corpus. Underlying Web-text permission was not obtained; raw text is not redistributed here or admitted to release data.",
    },
    "ndl": {
        "url": "https://lab.ndl.go.jp/dataset/huriganacorpus/aozora_dataset.zip",
        "sha256": "8df5aa0722686cf6c9a2a87f4786ceb5453be8c7a7fe1259c457b1f261215500",
        "terms": "https://github.com/ndl-lab/huriganacorpus-aozora/blob/c20b60bc2a6fcdfd3964b71ed9e3a46c6e330e8d/LICENSE",
        "use": "Publisher's No Copyright declaration with jurisdiction/moral-rights caveats retained at the terms URL. Local validation only; no release dictionary admission.",
    },
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ranked(value):
    return hashlib.sha256((SEED + value).encode()).digest()


def download(name, source):
    path = WORK / (name + ".zip")
    if path.exists() and sha(path) == source["sha256"]:
        return path
    start = path.stat().st_size if path.exists() else 0
    headers = {"User-Agent": "Accompanist Japanese corpus validation"}
    if start:
        headers["Range"] = f"bytes={start}-"
    with urllib.request.urlopen(urllib.request.Request(source["url"], headers=headers), timeout=60) as response:
        if response.status == 206:
            if not response.headers.get("Content-Range", "").startswith(f"bytes {start}-"):
                raise ValueError("Unexpected download range")
            mode = "ab"
        else:
            mode = "wb"
        with path.open(mode) as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    if sha(path) != source["sha256"]:
        raise ValueError(f"Incomplete or changed corpus: {path}; pinned hash must match")
    return path


def han(text):
    return any(0x3400 <= ord(c) <= 0x9FFF or 0xF900 <= ord(c) <= 0xFAFF or
               0x20000 <= ord(c) <= 0x33479 or c == "々" for c in text)


def cjk(text):
    return han(text) or any("ぁ" <= c <= "ゖ" or "ァ" <= c <= "ヺ" or
                            "\uff66" <= c <= "\uff9f" or c == "ー" for c in text)


def norm(text):
    return "".join(chr(ord(c) - 0x60) if "ァ" <= c <= "ヶ" or c in "ヽヾ" else c
                   for c in unicodedata.normalize("NFKC", text))


def record(identifier, text, tokens, corpus, **extra):
    offset = 0
    for token in tokens:
        token["start"] = offset
        offset += len(token["surface"].encode("utf-16le")) // 2
        token["end"] = offset
    assert offset == len(text.encode("utf-16le")) // 2
    return dict(id=identifier, text=text, tokens=tokens, corpus=corpus, **extra)


def prepare():
    WORK.mkdir(parents=True, exist_ok=True)
    rows, sampling = [], {}
    with zipfile.ZipFile(download("kwdlc", SOURCES["kwdlc"])) as archive:
        names = archive.namelist()
        ids = set(archive.read(names[0] + "id/test.id").decode("utf-8").splitlines())
        count, alternatives = 0, 0
        for name in sorted(n for n in names if "/knp/" in n and n.endswith(".knp") and Path(n).stem in ids):
            tokens = []
            for line in archive.read(name).decode("utf-8").splitlines():
                if line.startswith("# S-ID:"):
                    identifier = line.split()[1].removeprefix("S-ID:")
                    tokens = []
                elif line == "EOS":
                    text = "".join(t["surface"] for t in tokens)
                    if han(text):
                        rows.append(record("kwdlc:" + identifier, text, tokens, "kwdlc"))
                        count += 1
                elif line.startswith("@ "):
                    fields = line[2:].split()
                    if len(fields) >= 2 and tokens and fields[0] == tokens[-1]["surface"] and fields[1] not in tokens[-1]["readings"]:
                        tokens[-1]["readings"].append(fields[1])
                        alternatives += 1
                elif line and not line.startswith(("#", "* ", "+ ")):
                    fields = line.split()
                    if len(fields) < 11:
                        raise ValueError(f"Unsupported JUMAN row: {name}")
                    tokens.append(dict(surface=fields[0], readings=[fields[1]], pos=fields[3], kind=fields[5]))
        sampling["kwdlc"] = dict(official_test_documents=len(ids), sentences_with_han=count, alternative_readings=alternatives)
    with zipfile.ZipFile(download("ndl", SOURCES["ndl"])) as archive:
        books = [n for n in archive.namelist() if n.endswith(".txt") and "MacOS" not in n]
        by_author = defaultdict(list)
        for name in books:
            by_author[name.split("/")[1]].append(name)
        authors = sorted(by_author, key=ranked)[:80]
        selected, rejected = [], Counter()
        for author in authors:
            for name in sorted(by_author[author], key=ranked)[:2]:
                eligible = []
                blocks = re.split(r"(?m)^行番号:", archive.read(name).decode("utf-8").replace("\r", ""))[1:]
                for block in blocks:
                    lines = [line for line in block.splitlines() if line]
                    number = lines[0].split("\t")[0].strip()
                    text_line = next((line for line in lines if line.endswith("[入力文]")), None)
                    if text_line is None:
                        rejected["missing_text"] += 1
                        continue
                    text = text_line.split("\t")[0]
                    if not han(text):
                        rejected["no_han"] += 1
                        continue
                    if not 5 <= len(text) <= 160:
                        rejected["length_outside_5_160"] += 1
                        continue
                    tokens = []
                    for line in lines[3:]:
                        fields = line.split("\t")
                        if len(fields) != 3:
                            raise ValueError(f"Unsupported NDL row: {name}")
                        if fields[2] == "分かち書き" and not fields[0]:
                            continue
                        tokens.append(dict(surface=fields[0], readings=[fields[1]], pos=fields[2], kind=fields[2]))
                    if "".join(t["surface"] for t in tokens) != text:
                        rejected["source_alignment_mismatch"] += 1
                        continue
                    identifier = "ndl:" + name + ":" + number
                    eligible.append(record(identifier, text, tokens, "ndl", author=author, book=name))
                selected.extend(heapq.nsmallest(25, eligible, key=lambda r: ranked(r["id"])))
        rows.extend(selected)
        sampling["ndl"] = dict(corpus_books=len(books), corpus_authors=len(by_author), selected_authors=len(authors),
                               sample_books=len({r["book"] for r in selected}), sentences=len(selected), filters=dict(rejected),
                               sampling="SHA256 seed Accompanist-ja-v1: first 80 authors, up to 2 books per author, up to 25 aligned Han-containing 5-160 scalar sentences per book; ranked without model predictions")
    assert len({r["id"] for r in rows}) == len(rows)
    (WORK / "gold.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), "utf-8")
    (WORK / "input.tsv").write_text("# role=validation-only; NEVER a release dictionary input\n" +
                                   "".join(r["id"] + "\t" + r["text"] + "\n" for r in rows), "utf-8")
    (WORK / "sampling.json").write_text(json.dumps(sampling, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(f"Prepared {len(rows)} fixed validation sentences", flush=True)


def vowel(char):
    for value, characters in (("あ", "あかがさざただなはばぱまやらわぁゃゎ"), ("い", "いきぎしじちぢにひびぴみりゐぃ"),
                              ("う", "うくぐすずつづぬふぶぷむゆるゔぅゅ"), ("え", "えけげせぜてでねへべぺめれゑぇ"),
                              ("お", "おこごそぞとどのほぼぽもよろをぉょ")):
        if char in characters:
            return value
    return None


def reading_pattern(token, corpus):
    readings = [norm(r) for r in token["readings"]]
    if any(not re.fullmatch("[ぁ-ゖーゝゞ]+", r) for r in readings):
        return None
    if corpus == "kwdlc" and token["pos"] == "助詞" and token["surface"] in ("は", "へ", "を"):
        readings = sorted(set(readings + [{"は": "わ", "へ": "え", "を": "お"}[token["surface"]]]))
    patterns = []
    for reading in readings:
        pattern = ""
        for at, char in enumerate(reading):
            if char == "ー" and at and (value := vowel(reading[at - 1])):
                pattern += "[" + {"お": "おうー", "え": "えいー"}.get(value, value + "ー") + "]"
            else:
                pattern += re.escape(char)
        patterns.append(pattern)
    return "(?:" + "|".join(patterns) + ")"


def reading_groups(row, spans):
    boundaries = sorted(({0} | {t["end"] for t in row["tokens"]}) & ({0} | {t["end"] for t in spans}))
    for start, end in zip(boundaries, boundaries[1:]):
        tokens = [t for t in row["tokens"] if start <= t["start"] and t["end"] <= end]
        parts = [t for t in spans if start <= t["start"] and t["end"] <= end]
        yield start, end, tokens, parts


def sentence_match(row, prediction):
    matched, scored = True, False
    for _, _, tokens, parts in reading_groups(row, prediction["spans"]):
        if not cjk("".join(t["surface"] for t in tokens)):
            continue
        patterns = [reading_pattern(t, row["corpus"]) for t in tokens]
        if any(p is None for p in patterns):
            return None
        scored = True
        actual = "".join(norm(t["kana"]) for t in parts) if all(t["kana"] is not None for t in parts) else None
        matched &= actual is not None and re.fullmatch("".join(patterns), actual) is not None
    return matched if scored else None


def score(output, baseline_report=None, baseline_predictions=None):
    gold = [json.loads(line) for line in (WORK / "gold.jsonl").read_text("utf-8").splitlines()]
    predictions = [json.loads(line) for line in (WORK / "predictions.jsonl").read_text("utf-8").splitlines()]
    assert len(gold) == len(predictions)
    stats, errors = defaultdict(Counter), []
    for row, prediction in zip(gold, predictions):
        assert row["id"] == prediction["id"]
        spans = prediction["spans"]
        size = len(row["text"].encode("utf-16le")) // 2
        assert spans[0]["start"] == 0 and spans[-1]["end"] == size
        assert all(a["end"] == b["start"] for a, b in zip(spans, spans[1:]))
        counts = stats[row["corpus"]]
        counts["sentences"] += 1
        counts["format_boundary_sensitive_sentences"] += "".join(prediction["latin"].split()) != "".join(prediction["joinedLatin"].split())
        counts["extra_unknown_markers_from_word_formatting"] += prediction["latin"].count("_") > prediction["joinedLatin"].count("_")
        han_flags, cjk_flags, excluded = [], [], False
        for start, end, tokens, parts in reading_groups(row, spans):
            surface = "".join(t["surface"] for t in tokens)
            assert surface.encode("utf-16le") == row["text"].encode("utf-16le")[start * 2:end * 2]
            if not cjk(surface):
                counts["non_cjk_groups_ignored"] += 1
                continue
            patterns = [reading_pattern(t, row["corpus"]) for t in tokens]
            if any(p is None for p in patterns):
                counts["groups_excluded_non_kana_reference"] += 1
                excluded = True
                continue
            expected = "".join(norm(t["readings"][0]) for t in tokens)
            actual = "".join(norm(t["kana"]) for t in parts) if all(t["kana"] is not None for t in parts) else None
            pattern = re.compile("".join(patterns))
            correct = actual is not None and pattern.fullmatch(actual) is not None
            counts["cjk_groups"] += 1
            counts["cjk_matched"] += correct
            counts["strict_cjk_matched"] += actual == expected
            cjk_flags.append(correct)
            if not han(surface):
                continue
            counts["han_groups"] += 1
            counts["han_matched"] += correct
            counts["strict_han_matched"] += actual == expected
            han_flags.append(correct)
            unknown = any(t["resolution"] == "UNKNOWN" for t in parts)
            ambiguous = any(t["resolution"] == "AMBIGUOUS" for t in parts)
            counts["han_unknown"] += unknown
            counts["han_ambiguous"] += ambiguous
            if len(parts) == 1:
                counts["han_single_span_groups"] += 1
                if not correct:
                    counts["han_single_span_wrong"] += 1
                    candidates = [c for c in parts[0]["candidates"] if pattern.fullmatch(norm(c["kana"])) is not None]
                    counts["wrong_single_span_with_reference_candidate"] += bool(candidates)
                    counts["wrong_single_span_with_reference_in_ambiguity_window"] += any(c.get("uncertaintyCostDelta", c["costDelta"]) <= 100 for c in candidates)
            if not correct:
                counts["han_wrong_resolved"] += not unknown and not ambiguous
                errors.append(dict(corpus=row["corpus"], id=row["id"], start=start, end=end, surface=surface,
                                   expected=expected, actual=actual, unknown=unknown, ambiguous=ambiguous,
                                   candidates=parts[0]["candidates"] if len(parts) == 1 else [],
                                   gold_pos=[t["pos"] + ":" + t["kind"] for t in tokens]))
        if excluded:
            counts["sentences_with_excluded_reference"] += 1
        else:
            counts["han_sentence_total"] += bool(han_flags)
            counts["han_sentence_matched"] += bool(han_flags) and all(han_flags)
            counts["cjk_sentence_total"] += bool(cjk_flags)
            counts["cjk_sentence_matched"] += bool(cjk_flags) and all(cjk_flags)
    (WORK / "errors.jsonl").write_text("".join(json.dumps(e, ensure_ascii=False) + "\n" for e in errors), "utf-8")
    for counts in stats.values():
        counts["han_group_match_percent"] = round(100 * counts["han_matched"] / counts["han_groups"], 4)
        counts["cjk_sentence_match_percent"] = round(100 * counts["cjk_sentence_matched"] / counts["cjk_sentence_total"], 4)
    common = {}
    for corpus in stats:
        frequency = Counter((e["surface"], e["expected"], e["actual"]) for e in errors if e["corpus"] == corpus)
        common[corpus] = [dict(surface=key[0], reference=key[1], selected=key[2], occurrences=count)
                          for key, count in frequency.most_common(20)]
    version = re.search(r'\bversion\s*=\s*"([^"]+)"', (ROOT / "build.gradle.kts").read_text("utf-8"))
    if version is None:
        raise ValueError("Cannot identify evaluated library version")
    report = dict(role="validation-only", evaluation="development-set: corpus diagnostics informed the fixes; not held-out accuracy",
                  library_version=version.group(1), sources=SOURCES,
                  sampling=json.loads((WORK / "sampling.json").read_text("utf-8")),
                  input_sha256=sha(WORK / "input.tsv"), gold_sha256=sha(WORK / "gold.jsonl"),
                  predictions_sha256=sha(WORK / "predictions.jsonl"),
                  japanese_pack=dict(bytes=(ROOT / "data-japanese/packs/ja-JP.lpd").stat().st_size,
                                     sha256=sha(ROOT / "data-japanese/packs/ja-JP.lpd")),
                  runtime_sources_sha256=hashlib.sha256(b"".join(p.read_bytes() for p in sorted((ROOT / "runtime/src/commonMain").rglob("*.kt")))).hexdigest(),
                  methods=["Full sentences parsed; shared gold/predicted UTF-16 boundaries define coarsened reading groups, without guessed kanji alignment.",
                           "Kana normalization uses NFKC and hiragana. Corpus long marks accept the matching vowel (including ou/ei spellings); JUMAN particle written/spoken readings are both accepted. Strict counts are also reported.",
                           "Non-CJK groups are ignored. Unsupported references (e.g. NDL Han numerals annotated as Arabic digits) are excluded, not model errors. Whole-sentence denominators exclude sentences with unsupported references.",
                           "Mismatch is annotation disagreement, not always a proven mistake: alternative valid readings (e.g. nihon/nippon), old orthography and noisy NDL annotations remain.",
                           "KWDLC analyses were manually corrected. NDL matches Braille against dictionary-generated candidates, including IPA; it is not wholly independent of this library's IPADIC data.",
                           "Boundary-sensitive Latin compares identical selected kana across separate/merged spans; apostrophe differences can be valid with spaces. Extra unknown markers isolate lost cross-word kana formatting.",
                           "For every sentence the JVM auditor asserted full ParseResult equality at cache 0, reused cache 256 and immediate warm cache 256, plus ASCII output.",
                           "These Web/literary corpora are not a lyrics test set or independent kana-to-ASCII formatter gold standard. Data and errors remain local-only build outputs; release packs were not changed."],
                  summary=dict(stats), common_disagreements=common)
    if baseline_report is not None:
        baseline = json.loads(baseline_report.read_text("utf-8"))
        if baseline["input_sha256"] != report["input_sha256"] or baseline["gold_sha256"] != report["gold_sha256"]:
            raise ValueError("Baseline must use the same fixed input and reference")
        if baseline["predictions_sha256"] != sha(baseline_predictions):
            raise ValueError("Baseline predictions must match the report hash")
        previous = [json.loads(line) for line in baseline_predictions.read_text("utf-8").splitlines()]
        if len(previous) != len(gold) or any(r["id"] != p["id"] for r, p in zip(gold, previous)):
            raise ValueError("Baseline sentence IDs must match")
        paired = defaultdict(Counter)
        for row, before, after in zip(gold, previous, predictions):
            old_match, new_match = sentence_match(row, before), sentence_match(row, after)
            counts = paired[row["corpus"]]
            if old_match is None or new_match is None:
                counts["excluded"] += 1
            else:
                counts["compared"] += 1
                counts["newly_matched"] += not old_match and new_match
                counts["regressed"] += old_match and not new_match
                counts["both_matched"] += old_match and new_match
                counts["both_unmatched"] += not old_match and not new_match
        report["comparison"] = dict(library_version=baseline["library_version"],
                                    japanese_pack=baseline["japanese_pack"],
                                    runtime_sources_sha256=baseline["runtime_sources_sha256"],
                                    predictions_sha256=baseline["predictions_sha256"],
                                    summary=baseline["summary"], paired_sentences=dict(paired))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", "utf-8")
    print(json.dumps(dict(stats), ensure_ascii=False, indent=2))
    for name in SOURCES:
        (WORK / (name + ".zip")).unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--score-only", action="store_true", help="Score existing local predictions without downloading or parsing again")
    parser.add_argument("--output", type=Path, default=ROOT / "benchmark/results/japanese-corpus.json")
    parser.add_argument("--baseline-report", type=Path, help="Compare a previous report using the identical fixed reference")
    parser.add_argument("--baseline-predictions", type=Path, help="Local predictions whose hash matches the previous report")
    args = parser.parse_args()
    if (args.baseline_report is None) != (args.baseline_predictions is None):
        parser.error("--baseline-report and --baseline-predictions must be supplied together")
    if not args.score_only:
        prepare()
        wrapper = "gradlew.bat" if os.name == "nt" else "gradlew"
        subprocess.run([str(ROOT / wrapper), ":benchmark:run",
                        "--args=--japanese-audit build/japanese-validation/input.tsv build/japanese-validation/predictions.jsonl",
                        "--console=plain"], cwd=ROOT, check=True)
    score(args.output, args.baseline_report, args.baseline_predictions)
