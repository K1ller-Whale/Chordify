"""Build the mockup's analysis JSON from a real Billboard annotation.

The output follows the `AnalysisResult` schema from 05-api-and-communication.md,
so the mockup page renders exactly what the v2 API will return. Chords, beats,
sections and key come from the human annotation; next-chord predictions come
from the baseline progression model (key-relative 4-gram trained on the other
889 Billboard annotations + this song's own history).

    python make_mockup_data.py --choco path/to/choco
"""
import argparse
import collections
import json
import os
import re

import billboard_stats as bb

HERE = os.path.dirname(os.path.abspath(__file__))
MOCKUP = os.path.join(HERE, "..", "mockup", "analysis-view.html")
SONG_ID = "0681"  # "With Or Without You" - U2, Billboard id 0681

ROMAN = {0: "I", 1: "bII", 2: "II", 3: "bIII", 4: "III", 5: "IV", 6: "#IV", 7: "V", 8: "bVI", 9: "VI", 10: "bVII", 11: "VII"}
FUNCTION = {0: "tonic", 4: "tonic", 9: "tonic", 2: "subdominant", 5: "subdominant", 7: "dominant", 11: "dominant"}
MODES = {0: "Ionian", 2: "Dorian", 4: "Phrygian", 5: "Lydian", 7: "Mixolydian", 9: "Aeolian", 11: "Locrian"}
QUALITY_DISPLAY = {"maj": "", "min": "m", "7": "7", "min7": "m7", "maj7": "maj7", "maj9": "maj9", "sus4(b7)": "7sus4",
                   "sus4": "sus4", "min9": "m9", "maj6": "6", "dim": "dim", "aug": "aug", "hdim7": "m7b5", "5": "5"}
MINOR_FAMILY = bb.MIN_FAMILY
NAMED_PATTERNS = {("I", "V", "vi", "IV"): "I–V–vi–IV (the “Axis” progression)",
                  ("I", "vi", "IV", "V"): "I–vi–IV–V (the ’50s progression)",
                  ("ii", "V", "I"): "ii–V–I", ("i", "bVII", "bVI", "V"): "Andalusian cadence",
                  ("I", "IV", "I", "IV"): "I–IV vamp"}
THEORY = {("V", "I"): "authentic cadence V→I", ("V", "vi"): "deceptive cadence V→vi",
          ("IV", "I"): "plagal motion IV→I", ("ii", "V"): "pre-dominant → dominant",
          ("vi", "IV"): "vi→IV, the Axis loop", ("IV", "V"): "subdominant → dominant",
          ("I", "IV"): "tonic → subdominant", ("I", "V"): "tonic → dominant", ("I", "vi"): "tonic → relative minor",
          ("vi", "ii"): "descending fifths", ("IV", "vi"): "subdominant → relative minor", ("V", "IV"): "rock “backdoor” V→IV"}


def is_minor(quality):
    return quality.split("(")[0] in MINOR_FAMILY


def display(label):
    if label == "N":
        return "N.C."
    root, _, rest = label.partition(":")
    quality, _, bass = (rest or "maj").partition("/")
    return root + QUALITY_DISPLAY.get(quality, quality) + (f"/{bass}" if bass else "")


def roman(label, tonic_pc):
    root_pc, quality, _ = bb.parse_harte(label)
    numeral = ROMAN[(root_pc - tonic_pc) % 12]
    if is_minor(quality):
        numeral = re.sub(r"[IV]+", lambda m: m.group(0).lower(), numeral)
    return numeral


def lm_token(label, tonic_pc):
    """Key-relative token used by the baseline LM; sus/power chords count as major."""
    root_pc, quality, _ = bb.parse_harte(label)
    if root_pc is None:
        return None
    return bb.DEGREES[(root_pc - tonic_pc) % 12] + ("m" if is_minor(quality) else "")


def token_to_roman(token):
    return re.sub(r"[IV]+", lambda m: m.group(0).lower(), token[:-1]) if token.endswith("m") else token


def salami_timeline(path):
    """Bars (with beats) and sections from salami_chords.txt."""
    lines = []
    for raw in open(path, encoding="utf-8"):
        raw = raw.strip()
        if not raw or raw.startswith("#"):
            continue
        t, _, content = raw.partition("\t")
        lines.append((float(t), content))
    bars, sections = [], []
    for (start, content), (end, _) in zip(lines, lines[1:]):
        section = re.match(r"^([A-Z]'*),\s*([a-z\- ]+),", content)
        if section:
            sections.append({"letter": section.group(1), "label": section.group(2).strip(), "start": round(start, 3)})
        cells = re.findall(r"\|([^|]*)(?=\|)", content)
        for k, cell in enumerate(cells):
            bar_start = start + k * (end - start) / len(cells)
            bar_end = start + (k + 1) * (end - start) / len(cells)
            bars.append({"start": round(bar_start, 3), "end": round(bar_end, 3), "chords": cell.split()})
    for a, b in zip(sections, sections[1:] + [None]):
        a["end"] = b["start"] if b else bars[-1]["end"]
    return bars, sections


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--choco", required=True)
    args = parser.parse_args()
    root = os.path.join(args.choco, "partitions/billboard")
    songs = bb.load_jams(root)
    salami = bb.load_salami(root)
    meta = {os.path.basename(r["jams_path"]): r for r in bb.csv.DictReader(open(os.path.join(root, "choco/meta.csv")))}
    song = next(s for s in songs if meta[s["jams"]]["billboard_id"] == SONG_ID)
    title = meta[song["jams"]]["track_title"].strip()

    # Baseline LM trained on every other song (duplicates of this title excluded).
    train = []
    for s in songs:
        if meta[s["jams"]]["track_title"].strip().lower() == title.lower():
            continue
        tonic = bb.pitch_class(salami[meta[s["jams"]]["billboard_id"]]["tonics"][0][1].split(":")[0])
        seq = [lm_token(l, tonic) for l, _, _ in bb.merge_repeats(s["obs"]) if l not in ("N", "X")]
        train.append(bb.dedupe([x for x in seq if x]))
    ngram = bb.BackoffNgram(4, train)

    tonic_name = salami[SONG_ID]["tonics"][0][1]
    tonic_pc = bb.pitch_class(tonic_name)
    bars, sections = salami_timeline(os.path.join(root, "raw/original", SONG_ID, "salami_chords.txt"))
    beats, downbeats = [], []
    for bar in bars:
        step = (bar["end"] - bar["start"]) / 4
        downbeats.append(bar["start"])
        beats.extend(round(bar["start"] + i * step, 3) for i in range(4))
    bpm = 60 / (sum(b["end"] - b["start"] for b in bars) / len(bars) / 4)

    chords, history = [], []
    for i, (label, start, duration) in enumerate(bb.merge_repeats(song["obs"])):
        entry = {"index": i, "start": round(start, 3), "end": round(start + duration, 3), "label": label,
                 "display": display(label), "confidence": 1.0}
        if label != "N":
            root_pc, quality, bass = bb.parse_harte(label)
            degree = (root_pc - tonic_pc) % 12
            entry.update({"root": label.split(":")[0], "quality": quality, "roman": roman(label, tonic_pc),
                          "function": FUNCTION.get(degree, "borrowed"),
                          "scale_hint": f"{label.split(':')[0]} {MODES[degree]}" if degree in MODES else None,
                          "beats": sum(1 for b in beats if start - 0.05 <= b < start + duration - 0.05)})
        chords.append(entry)

    # Next-chord predictions at every chord change: song history (cache) mixed with the n-gram prior.
    seen_in_song = {}
    for c in chords:
        if c["label"] != "N":
            seen_in_song.setdefault(lm_token(c["label"], tonic_pc), c)
    for c in chords:
        if c["label"] == "N":
            continue
        token = lm_token(c["label"], tonic_pc)
        if not history or history[-1] != token:
            history.append(token)
        t = len(history)
        cache = collections.Counter()
        context_len = 0
        for k in range(min(6, t), 0, -1):
            ctx = history[t - k:t]
            for j in range(k, t):
                if history[j - k:j] == ctx:
                    cache[history[j]] += 1
            if cache:
                context_len = k
                break
        prior = collections.Counter()
        for k in range(3, -1, -1):
            if t - k < 0:
                continue
            cont = ngram.counts[k].get(tuple(history[t - k:t]))
            if cont and sum(cont.values()) >= 3:
                prior = cont
                break
        mixed = collections.Counter()
        for dist, weight in ((cache, 0.75 if cache else 0.0), (prior, 0.25 if cache else 1.0)):
            total = sum(dist.values())
            for tok, v in dist.items():
                mixed[tok] += weight * v / total
        preds = []
        for tok, p in mixed.most_common(3):
            rn = token_to_roman(tok)
            known = seen_in_song.get(tok)
            degree = bb.DEGREES.index(tok.rstrip("m"))
            name = bb.NAMES[(tonic_pc + degree) % 12] + ("m" if tok.endswith("m") else "")
            reasons = []
            if cache[tok]:
                reasons.append(f"followed this context {cache[tok]}× earlier in the song")
            theory = THEORY.get((c["roman"].rstrip("7"), rn))
            if theory:
                reasons.append(theory)
            if not reasons:
                reasons.append("common continuation in the training corpus")
            preds.append({"display": known["display"] if known else name, "label": known["label"] if known else None,
                          "roman": rn, "p": round(p, 3), "reason": "; ".join(reasons)})
        c["next"] = preds
        c["theory_only"] = [{"roman": token_to_roman(tok), "p": round(v / sum(prior.values()), 3)}
                            for tok, v in prior.most_common(3)] if prior else []

    real = [c for c in chords if c["label"] != "N"]
    romans = [c["roman"] for c in real]
    loops = collections.Counter(tuple(romans[i:i + 4]) for i in range(len(romans) - 3))
    patterns = []
    for pattern, count in loops.most_common():
        if count < 3 or any(set(pattern) == set(p["roman"]) and len(p["roman"]) == 4 for p in patterns):
            continue
        base = tuple(re.sub(r"(7sus4|maj9|m7|7|maj7)$", "", r) for r in pattern)
        patterns.append({"roman": list(pattern), "name": NAMED_PATTERNS.get(base), "count": count})
        if len(patterns) == 3:
            break
    share = collections.Counter()
    for c in real:
        share[c["display"]] += c["end"] - c["start"]
    total = sum(share.values())

    result = {
        "analysis_id": "an_mockup_billboard_0681",
        "status": "completed",
        "source": {"title": title, "artist": meta[song["jams"]]["track_performer"], "duration": song["duration"]},
        "models": {"note": "mockup: chords/beats/sections/key are the human Billboard annotation; predictions are the n-gram + song-history baseline"},
        "tempo": {"bpm": round(bpm, 1), "meter": salami[SONG_ID]["metres"][0]},
        "key": {"global": {"tonic": tonic_name, "mode": "major", "confidence": 1.0},
                "segments": [{"start": 0.0, "end": song["duration"], "tonic": tonic_name, "mode": "major"}]},
        "beats": beats, "downbeats": downbeats,
        "bars": [{"start": b["start"], "end": b["end"], "chords": [display(x) for x in b["chords"]]} for b in bars],
        "sections": sections,
        "chords": chords,
        "summary": {"unique_chords": len(share),
                    "time_share": [{"display": k, "seconds": round(v, 2), "share": round(v / total, 4)} for k, v in share.most_common()],
                    "patterns": patterns},
    }
    html = open(MOCKUP).read()
    payload = json.dumps(result, separators=(",", ":"))
    html = re.sub(r'(<script id="analysis-data" type="application/json">)(.*?)(</script>)',
                  lambda m: m.group(1) + payload + m.group(3), html, flags=re.S)
    open(MOCKUP, "w").write(html)
    print(f"wrote {len(chords)} chords, {len(beats)} beats, {len(sections)} sections, bpm={bpm:.1f}; patterns={patterns}")
    example = next(c for c in chords if c.get("roman") == "V" and c["start"] > 90)
    print(json.dumps(example, indent=1))


if __name__ == "__main__":
    main()
