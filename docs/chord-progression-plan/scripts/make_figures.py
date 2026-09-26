"""Render the dataset charts used in docs/chord-progression-plan (light + dark PNGs).

    python make_figures.py            # reads ../assets/billboard_stats.json
"""
import json
import os
import re

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib.colors import LinearSegmentedColormap  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, "..", "assets")

THEMES = {
    "light": {"surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "muted": "#898781",
              "grid": "#e1e0d9", "axis": "#c3c2b7", "s1": "#2a78d6", "s2": "#eb6834",
              "neutral": "#c3c2b7", "ramp": ["#fcfcfb", "#cde2fb", "#86b6ef", "#3987e5", "#1c5cab", "#0d366b"]},
    "dark": {"surface": "#1a1a19", "ink": "#ffffff", "ink2": "#c3c2b7", "muted": "#898781",
             "grid": "#2c2c2a", "axis": "#383835", "s1": "#3987e5", "s2": "#d95926",
             "neutral": "#52514e", "ramp": ["#1a1a19", "#104281", "#1c5cab", "#2a78d6", "#6da7ec", "#cde2fb"]},
}


def style(ax, t, grid_axis="y"):
    ax.set_facecolor(t["surface"])
    for side in ("top", "right", "left"):
        ax.spines[side].set_visible(False)
    ax.spines["bottom"].set_color(t["axis"])
    ax.tick_params(colors=t["muted"], length=0, labelsize=9)
    ax.grid(axis=grid_axis, color=t["grid"], linewidth=0.8)
    ax.set_axisbelow(True)


def figure(t, w=8.0, h=4.2):
    fig, ax = plt.subplots(figsize=(w, h), dpi=200)
    fig.patch.set_facecolor(t["surface"])
    return fig, ax


def title(fig, t, text, sub):
    fig.text(0.012, 0.965, text, color=t["ink"], fontsize=12.5, fontweight="bold", va="top")
    fig.text(0.012, 0.905, sub, color=t["ink2"], fontsize=9.5, va="top")


def save(fig, name, mode):
    fig.savefig(os.path.join(ASSETS, f"{name}-{mode}.png"), facecolor=fig.get_facecolor())
    plt.close(fig)


def chord_durations(d, t, mode):
    c = d["chord_changes"]
    edges = np.array(c["hist_edges"])
    counts = np.array(c["hist_counts"])
    window = d["current_samples"]["window_s"]
    fig, ax = figure(t)
    ax.bar(edges[:-1], counts, width=0.25 - 0.03, align="edge", color=t["s1"], linewidth=0)
    ax.axvline(window, color=t["ink2"], linewidth=1)
    ax.text(window + 0.08, counts.max() * 0.93, f"current model window\n{window:.2f} s",
            color=t["ink2"], fontsize=9, va="top")
    ax.text(window + 0.08, counts.max() * 0.72,
            f"{c['share_shorter_than_window']:.1%} of chords end before the window does\n"
            f"median {c['median_s']:.2f} s · {c['share_shorter_than_1s']:.0%} shorter than 1 s",
            color=t["ink"], fontsize=9.5)
    ax.set_xlim(0, 8)
    ax.set_xticks(range(0, 9))
    ax.set_xticklabels([f"{i} s" for i in range(8)] + ["8 s+"])
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    style(ax, t)
    fig.subplots_adjust(left=0.08, right=0.98, top=0.8, bottom=0.1)
    title(fig, t, "How long does a chord last? (Billboard, 89,617 chord changes)",
          "Duration between chord changes, 0.25 s bins. Longest bin collects everything ≥ 8 s.")
    save(fig, "fig-chord-durations", mode)


def vocabulary(d, t, mode):
    share = d["vocabulary"]["quality_share"]
    top = ["maj", "min", "7", "min7", "N", "maj7", "5", "1", "maj(9)", "maj6", "sus4", "min9"]
    labels = {"N": "N (no chord)", "5": "5 (power chord)", "1": "1 (single note)"}
    values = [share[q] for q in top] + [1 - sum(share[q] for q in top)]
    names = [labels.get(q, q) for q in top] + ["all other qualities"]
    colors = [t["s1"] if q in ("maj", "min") else t["neutral"] for q in top] + [t["neutral"]]
    fig, ax = figure(t, h=4.6)
    y = np.arange(len(values))[::-1]
    ax.barh(y, values, height=0.62, color=colors, linewidth=0)
    for yi, v in zip(y, values):
        ax.text(v + 0.006, yi, f"{v:.1%}", va="center", fontsize=8.5, color=t["ink2"])
    ax.set_yticks(y)
    ax.set_yticklabels(names, fontsize=9, color=t["ink2"])
    ax.set_xlim(0, 0.56)
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    style(ax, t, grid_axis="x")
    ax.spines["bottom"].set_visible(False)
    h1 = plt.Rectangle((0, 0), 1, 1, color=t["s1"])
    h2 = plt.Rectangle((0, 0), 1, 1, color=t["neutral"])
    leg = ax.legend([h1, h2], ["expressible by the current 24-class model", "not expressible today"],
                    loc="lower right", frameon=False, fontsize=9)
    for text in leg.get_texts():
        text.set_color(t["ink2"])
    fig.subplots_adjust(left=0.2, right=0.98, top=0.83, bottom=0.07)
    title(fig, t, "Share of annotated song time by chord quality",
          f"Plain major/minor triads cover {d['vocabulary']['plain_triad_share']:.0%} of the time; "
          f"slash chords (inversions) {d['vocabulary']['inversion_share']:.1%}.")
    save(fig, "fig-vocabulary", mode)


def class_imbalance(d, t, mode):
    counts = d["imbalance"]["class_counts"]
    keep = d["imbalance"]["undersample_keep_per_class"]
    names = list(counts)
    values = np.array([counts[n] for n in names])
    fig, ax = figure(t, w=10.8, h=4.4)
    x = np.arange(len(names))
    ax.bar(x, np.minimum(values, keep), width=0.7, color=t["s1"], linewidth=0, label="kept after undersampling")
    ax.bar(x, values - keep, bottom=keep + 60, width=0.7, color=t["neutral"], linewidth=0,
           label="discarded by undersampling")
    ax.set_xticks(x)
    ax.set_xticklabels([n.replace(":maj", "").replace(":min", "m") for n in names], fontsize=7.5)
    ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{int(v):,}"))
    style(ax, t)
    leg = ax.legend(loc="upper right", frameon=False, fontsize=9)
    for text in leg.get_texts():
        text.set_color(t["ink2"])
    fig.subplots_adjust(left=0.08, right=0.98, top=0.8, bottom=0.1)
    title(fig, t, "Training samples per class in the current pipeline (one per LAB line)",
          f"Balancing by undersampling keeps {keep} per class and throws away "
          f"{d['imbalance']['discarded_share']:.0%} of the data.")
    save(fig, "fig-class-imbalance", mode)


def next_chord(d, t, mode):
    r = d["next_chord"]
    models = ["unigram", "bigram", "trigram", "4-gram", "6-gram", "song-cache+4-gram"]
    names = ["unigram", "bigram", "trigram", "4-gram", "6-gram", "4-gram + same-song\nhistory"]
    rel = [r[f"key_relative/{m}"]["top1"] for m in models]
    ab = [r[f"absolute/{m}"]["top1"] for m in models]
    fig, ax = figure(t, h=4.6)
    y = np.arange(len(models))[::-1] * 1.0
    ax.barh(y + 0.19, rel, height=0.34, color=t["s1"], linewidth=0, label="key-relative (Roman numerals)")
    ax.barh(y - 0.19, ab, height=0.34, color=t["s2"], linewidth=0, label="absolute chord names")
    for yi, v in zip(y, rel):
        ax.text(v + 0.008, yi + 0.19, f"{v:.0%}", va="center", fontsize=8.5, color=t["ink2"])
    ax.set_yticks(y)
    ax.set_yticklabels(names, fontsize=9, color=t["ink2"])
    ax.set_xlim(0, 1)
    ax.xaxis.set_major_formatter(matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    style(ax, t, grid_axis="x")
    ax.spines["bottom"].set_visible(False)
    leg = ax.legend(loc="upper right", frameon=False, fontsize=9)
    for text in leg.get_texts():
        text.set_color(t["ink2"])
    fig.subplots_adjust(left=0.2, right=0.98, top=0.83, bottom=0.07)
    title(fig, t, "Next-chord top-1 accuracy on held-out Billboard songs",
          "Song-level 80/20 split, chord changes only, major/minor vocabulary.")
    save(fig, "fig-next-chord", mode)


def roman(degree):
    """'VIm' -> 'vi', 'bVII' -> 'bVII' (minor chords in lower case)."""
    return re.sub(r"[IV]+", lambda m: m.group(0).lower(), degree[:-1]) if degree.endswith("m") else degree


def transitions(d, t, mode):
    tr = d["transitions"]
    names = tr["degrees"]
    m = np.array(tr["p_next_given_prev"])
    cmap = LinearSegmentedColormap.from_list("seq", t["ramp"])
    fig, ax = figure(t, w=6.4, h=5.6)
    im = ax.imshow(m, cmap=cmap, vmin=0, vmax=0.55)
    for i in range(len(names)):
        for j in range(len(names)):
            if m[i, j] >= 0.15:
                r, g, b, _ = im.cmap(im.norm(m[i, j]))
                luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b
                ax.text(j, i, f"{m[i, j]:.0%}", ha="center", va="center", fontsize=8,
                        color="#0b0b0b" if luminance > 0.45 else "#ffffff")
    labels = [roman(n) for n in names]
    ax.set_xticks(range(len(names)))
    ax.set_xticklabels(labels, fontsize=9, color=t["ink2"])
    ax.set_yticks(range(len(names)))
    ax.set_yticklabels(labels, fontsize=9, color=t["ink2"])
    ax.set_xlabel("next chord", color=t["muted"], fontsize=9)
    ax.set_ylabel("current chord", color=t["muted"], fontsize=9)
    ax.tick_params(length=0)
    for s in ax.spines.values():
        s.set_visible(False)
    cb = fig.colorbar(im, ax=ax, fraction=0.04, pad=0.02, format=matplotlib.ticker.PercentFormatter(1.0, decimals=0))
    cb.outline.set_visible(False)
    cb.ax.tick_params(colors=t["muted"], labelsize=8, length=0)
    fig.subplots_adjust(left=0.1, right=0.95, top=0.84, bottom=0.1)
    title(fig, t, "P(next | current) for the 10 most common chords",
          "Key-relative Roman numerals, Billboard. Only chord changes count, so the diagonal is empty.")
    save(fig, "fig-transitions", mode)


def main():
    d = json.load(open(os.path.join(ASSETS, "billboard_stats.json")))
    plt.rcParams["font.family"] = "DejaVu Sans"
    for mode, t in THEMES.items():
        chord_durations(d, t, mode)
        vocabulary(d, t, mode)
        class_imbalance(d, t, mode)
        next_chord(d, t, mode)
        transitions(d, t, mode)


if __name__ == "__main__":
    main()
