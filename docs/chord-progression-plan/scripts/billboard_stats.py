"""Statistics of the McGill Billboard chord annotations used by the plan.

Every number quoted in docs/chord-progression-plan comes from this script.

Data source: the ChoCo mirror of Billboard (890 JAMS files + the original
salami_chords.txt files), which is reachable without Kaggle credentials:

    git clone --depth 1 --filter=blob:none --no-checkout https://github.com/smashub/choco.git
    cd choco
    git sparse-checkout set --no-cone 'partitions/billboard/choco/*' 'partitions/billboard/raw/original/*'
    git checkout HEAD

Usage:
    python billboard_stats.py --choco path/to/choco --out ../assets/billboard_stats.json
"""
import argparse
import collections
import csv
import glob
import json
import os
import random
import re
import statistics as st

import numpy as np

# The current model looks at 100 NNLS-chroma frames computed at 44.1 kHz with a
# 2048-sample hop (the Billboard feature settings).
WINDOW_S = 100 * 2048 / 44100

PC = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}
NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
DEGREES = ["I", "bII", "II", "bIII", "III", "IV", "#IV", "V", "bVI", "VI", "bVII", "VII"]
MAJ_FAMILY = {"maj", "7", "maj7", "maj6", "9", "maj9", "11", "13", "maj13", "maj11", "6"}
MIN_FAMILY = {"min", "min7", "min6", "min9", "minmaj7", "min11", "min13"}


def pitch_class(note):
    value = PC[note[0]]
    for accidental in note[1:]:
        value += 1 if accidental == "#" else -1 if accidental == "b" else 0
    return value % 12


def parse_harte(label):
    """'A:min7/b3' -> (9, 'min7', 'b3'); 'N' -> (None, 'N', None)."""
    if label in ("N", "X"):
        return None, label, None
    root, _, rest = label.partition(":")
    quality, _, bass = rest.partition("/")
    return pitch_class(root), (quality or "maj"), bass or None


def to_majmin(label):
    """MIREX-style majmin reduction: maj-like -> X:maj, min-like -> X:min, else 'X'."""
    root, quality, _ = parse_harte(label)
    if root is None:
        return quality
    base = quality.split("(")[0]
    if base in MAJ_FAMILY:
        return f"{NAMES[root]}:maj"
    if base in MIN_FAMILY:
        return f"{NAMES[root]}:min"
    return "X"


def merge_repeats(observations, key=lambda label: label):
    """Collapse consecutive identical (mapped) labels into [label, start, duration]."""
    merged = []
    for obs in observations:
        label = key(obs["value"])
        if merged and merged[-1][0] == label:
            merged[-1][2] += obs["duration"]
        else:
            merged.append([label, obs["time"], obs["duration"]])
    return merged


def load_jams(root):
    songs = []
    for path in sorted(glob.glob(os.path.join(root, "choco/jams/*.jams"))):
        jam = json.load(open(path))
        chords = next(a for a in jam["annotations"] if a["namespace"] == "chord")["data"]
        songs.append({"jams": os.path.basename(path), "duration": jam["file_metadata"]["duration"], "obs": chords})
    return songs


def load_salami(root):
    """Tonic timeline, metre, bar and section info from the original salami_chords.txt files."""
    info = {}
    for song_dir in sorted(os.listdir(os.path.join(root, "raw/original"))):
        path = os.path.join(root, "raw/original", song_dir, "salami_chords.txt")
        if not os.path.exists(path):
            continue
        tonics, metres, sections = [], [], []
        pending_tonic, bars, multi_chord_bars = None, 0, 0
        for line in open(path, encoding="utf-8", errors="ignore"):
            line = line.strip()
            if line.startswith("# tonic:"):
                pending_tonic = line.split(":", 1)[1].strip()
                continue
            if line.startswith("# metre:"):
                metres.append(line.split(":", 1)[1].strip())
                continue
            if not line or line.startswith("#"):
                continue
            time, _, content = line.partition("\t")
            try:
                time = float(time)
            except ValueError:
                continue
            if pending_tonic is not None:
                tonics.append((time, pending_tonic))
                pending_tonic = None
            section = re.match(r"^([A-Z]'*|Z'*),\s*([a-z\- ]+),", content)
            if section:
                sections.append(section.group(2).strip())
            for bar in re.findall(r"\|([^|]*)(?=\|)", content):
                tokens = [t for t in bar.split() if t != "."]
                if tokens:
                    bars += 1
                    multi_chord_bars += len(tokens) > 1
        info[song_dir] = {"tonics": tonics, "metres": metres, "sections": sections,
                          "bars": bars, "multi_chord_bars": multi_chord_bars}
    return info


def key_relative_sequences(songs, salami, meta):
    """Merged majmin chord-change sequences written as scale degrees of the local tonic."""
    sequences = []
    for song in songs:
        tonics = salami[meta[song["jams"]]["billboard_id"]]["tonics"]
        degrees = []
        for label, start, _ in merge_repeats(song["obs"], to_majmin):
            if label in ("N", "X"):
                continue
            tonic = tonics[0][1]
            for change_time, name in tonics:
                if change_time <= start + 1e-6:
                    tonic = name
            root, quality = label.split(":")
            degree = DEGREES[(NAMES.index(root) - pitch_class(tonic)) % 12]
            degrees.append(degree + ("" if quality == "maj" else "m"))
        absolute = [l for l, _, _ in merge_repeats(song["obs"], to_majmin) if l not in ("N", "X")]
        sequences.append((dedupe(degrees), dedupe(absolute)))
    return sequences


def dedupe(seq):
    return [x for i, x in enumerate(seq) if i == 0 or seq[i - 1] != x]


class BackoffNgram:
    """Most-frequent-continuation n-gram with backoff (count >= 3 to trust a context)."""

    def __init__(self, order, sequences):
        self.order = order
        self.counts = [collections.defaultdict(collections.Counter) for _ in range(order)]
        for seq in sequences:
            for t in range(1, len(seq)):
                for k in range(order):
                    if t - k < 0:
                        break
                    self.counts[k][tuple(seq[t - k:t])][seq[t]] += 1

    def rank(self, seq, t):
        for k in range(self.order - 1, -1, -1):
            if t - k < 0:
                continue
            continuation = self.counts[k].get(tuple(seq[t - k:t]))
            if continuation and sum(continuation.values()) >= 3:
                return [c for c, _ in continuation.most_common()]
        return []


def song_cache_rank(seq, t, max_context=6):
    """Predict from the same song's earlier history: longest matching context wins."""
    for k in range(min(max_context, t), 0, -1):
        context, followers = seq[t - k:t], collections.Counter()
        for j in range(k, t):
            if seq[j - k:j] == context:
                followers[seq[j]] += 1
        if followers:
            return [c for c, _ in followers.most_common()]
    return []


def evaluate(test, rank_fn):
    top1 = top3 = total = 0
    for seq in test:
        for t in range(1, len(seq)):
            ranked = rank_fn(seq, t)
            total += 1
            top1 += ranked[:1] == [seq[t]]
            top3 += seq[t] in ranked[:3]
    return {"top1": top1 / total, "top3": top3 / total, "n": total}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--choco", required=True, help="path to a ChoCo checkout")
    parser.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "../assets/billboard_stats.json"))
    parser.add_argument("--seed", type=int, default=0)
    args = parser.parse_args()
    root = os.path.join(args.choco, "partitions/billboard")
    random.seed(args.seed)

    songs = load_jams(root)
    salami = load_salami(root)
    meta = {os.path.basename(r["jams_path"]): r for r in csv.DictReader(open(os.path.join(root, "choco/meta.csv")))}
    out = {}

    unique = {(meta[s["jams"]]["track_title"].lower(), meta[s["jams"]]["track_performer"].lower()) for s in songs}
    out["corpus"] = {"annotations": len(songs), "unique_songs": len(unique),
                     "hours": sum(s["duration"] for s in songs) / 3600}

    # What the current pipeline turns into training samples: one sample per LAB line.
    lab_lines = [o["duration"] for s in songs for o in s["obs"] if o["value"] not in ("N", "X")]
    out["current_samples"] = {
        "window_s": WINDOW_S, "count": len(lab_lines), "median_s": st.median(lab_lines),
        "share_shorter_than_window": float(np.mean(np.array(lab_lines) < WINDOW_S)),
        "mean_window_fill": float(np.mean([min(d, WINDOW_S) / WINDOW_S for d in lab_lines])),
    }

    segments = [d for s in songs for (l, _, d) in merge_repeats(s["obs"]) if l not in ("N", "X")]
    edges = np.arange(0, 8.25, 0.25)
    hist, _ = np.histogram(np.clip(segments, 0, 7.999), bins=edges)
    per_minute = [len([x for x in merge_repeats(s["obs"]) if x[0] not in ("N", "X")]) / (s["duration"] / 60)
                  for s in songs]
    out["chord_changes"] = {
        "count": len(segments), "median_s": st.median(segments), "mean_s": st.mean(segments),
        "p10_s": float(np.percentile(segments, 10)), "p90_s": float(np.percentile(segments, 90)),
        "share_shorter_than_window": float(np.mean(np.array(segments) < WINDOW_S)),
        "share_shorter_than_1s": float(np.mean(np.array(segments) < 1)),
        "changes_per_minute_median": st.median(per_minute),
        "hist_edges": edges.tolist(), "hist_counts": hist.tolist(),
    }

    quality_time, majmin_time = collections.Counter(), collections.Counter()
    inversion_time = 0.0
    for s in songs:
        for o in s["obs"]:
            root_pc, quality, bass = parse_harte(o["value"])
            quality_time[quality if root_pc is not None else o["value"]] += o["duration"]
            majmin_time[to_majmin(o["value"])] += o["duration"]
            inversion_time += o["duration"] if bass else 0
    total_time = sum(quality_time.values())
    out["vocabulary"] = {
        "quality_share": {q: v / total_time for q, v in quality_time.most_common()},
        "inversion_share": inversion_time / total_time,
        "plain_triad_share": (quality_time["maj"] + quality_time["min"]) / total_time,
        "majmin_N_share": majmin_time["N"] / total_time,
        "majmin_X_share": majmin_time["X"] / total_time,
    }

    lab_counts = collections.Counter(to_majmin(o["value"]) for s in songs for o in s["obs"])
    class_counts = {k: v for k, v in lab_counts.items() if k not in ("N", "X")}
    keep = min(class_counts.values())
    class_time = {k: v for k, v in majmin_time.items() if k not in ("N", "X")}
    out["imbalance"] = {
        "class_counts": dict(sorted(class_counts.items(), key=lambda kv: -kv[1])),
        "undersample_keep_per_class": keep,
        "discarded_share": 1 - 24 * keep / sum(class_counts.values()),
        "max_min_time_ratio": max(class_time.values()) / min(class_time.values()),
        "major_time_share": sum(v for k, v in class_time.items() if k.endswith(":maj")) / sum(class_time.values()),
    }

    metres, sections, tonics = collections.Counter(), collections.Counter(), collections.Counter()
    for song in salami.values():
        metres.update(set(song["metres"]))
        sections.update(song["sections"])
        tonics[song["tonics"][0][1]] += 1
    bars = sum(s["bars"] for s in salami.values())
    out["structure"] = {
        "modulating_songs": sum(len({t for _, t in s["tonics"]}) > 1 for s in salami.values()),
        "songs": len(salami), "metres": dict(metres.most_common()),
        "multi_chord_bar_share": sum(s["multi_chord_bars"] for s in salami.values()) / bars,
        "sections": dict(sections.most_common(10)), "tonics": dict(tonics.most_common()),
    }

    sequences = key_relative_sequences(songs, salami, meta)
    unique_per_song = [len(set(a)) for _, a in sequences]
    order = list(range(len(sequences)))
    random.shuffle(order)
    cut = int(0.8 * len(order))
    results = {}
    for view, idx in (("key_relative", 0), ("absolute", 1)):
        train = [sequences[i][idx] for i in order[:cut]]
        test = [sequences[i][idx] for i in order[cut:]]
        for n, name in ((1, "unigram"), (2, "bigram"), (3, "trigram"), (4, "4-gram"), (6, "6-gram")):
            model = BackoffNgram(n, train)
            results[f"{view}/{name}"] = evaluate(test, model.rank)
        four_gram = BackoffNgram(4, train)
        results[f"{view}/song-cache+4-gram"] = evaluate(
            test, lambda seq, t: list(dict.fromkeys(song_cache_rank(seq, t) + four_gram.rank(seq, t))))
    out["next_chord"] = results
    out["unique_chords_per_song"] = {"median": st.median(unique_per_song),
                                     "p90": float(np.percentile(unique_per_song, 90))}

    degree_counts = collections.Counter(x for seq, _ in sequences for x in seq)
    top = [d for d, _ in degree_counts.most_common(10)]
    pairs = collections.Counter((seq[i - 1], seq[i]) for seq, _ in sequences for i in range(1, len(seq)))
    matrix = []
    for a in top:
        row_total = sum(v for (x, _), v in pairs.items() if x == a)
        matrix.append([pairs[(a, b)] / row_total for b in top])
    out["transitions"] = {"degrees": top, "p_next_given_prev": matrix,
                          "degree_share": {d: degree_counts[d] / sum(degree_counts.values()) for d in top}}

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w") as f:
        json.dump(out, f, indent=1)
    print(json.dumps({k: v for k, v in out.items() if k not in ("transitions",)}, indent=1)[:6000])


if __name__ == "__main__":
    main()
