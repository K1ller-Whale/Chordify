import json

import pytest

from chordify_ai.data import billboard, splits

SALAMI = """# title: Test Song
# artist: The Testers
# metre: 4/4
# tonic: C

0.0\tsilence
1.0\tA, intro, | C:maj | G:maj | A:min | F:maj |
9.0\tB, verse, | C:maj G:maj | F:maj . C:maj . | x2
# tonic: D
# metre: 3/4
17.0\tC, chorus, | D:maj | A:maj |
20.0\tZ, fadeout
30.0\tsilence
31.0\tend
"""


def test_parse_salami_sections_tonics_metres_and_repeats():
    parsed = billboard.parse_salami(SALAMI)
    assert parsed["header"]["title"] == "Test Song"
    assert parsed["tonics"] == [(0.0, "C"), (17.0, "D")]
    assert parsed["metres"] == [(0.0, "4/4"), (17.0, "3/4")]
    assert [(s["letter"], s["label"], s["start"], s["end"]) for s in parsed["sections"]] == [
        ("A", "intro", 1.0, 9.0), ("B", "verse", 9.0, 17.0), ("C", "chorus", 17.0, 20.0)]
    bars = parsed["bars"]
    # intro 4 bars + verse 2 bars x2 + chorus 2 bars
    assert len(bars) == 10
    assert bars[4]["chords"] == ["C:maj", "G:maj"] and bars[5]["chords"] == ["F:maj", "C:maj"]
    assert bars[6]["chords"] == ["C:maj", "G:maj"]  # repeat expanded
    assert bars[4]["start"] == 9.0 and bars[7]["end"] == 17.0
    assert [b["beats_per_bar"] for b in bars[-2:]] == [3, 3]


def test_stretched_final_phrase_repeats_at_the_typical_bar_length():
    text = "# metre: 4/4\n# tonic: C\n0.0\tA, verse, | C:maj | G:maj | C:maj | G:maj |\n" \
           "8.0\t| F:maj | C:maj |\n28.0\tsilence\n29.0\tend\n"
    bars = billboard.parse_salami(text)["bars"]
    assert all(b["end"] - b["start"] == pytest.approx(2.0) for b in bars)
    assert [b["chords"][0] for b in bars[4:8]] == ["F:maj", "C:maj", "F:maj", "C:maj"]
    assert bars[-1]["end"] == pytest.approx(28.0)


@pytest.mark.parametrize("metre, beats", [("4/4", 4), ("3/4", 3), ("12/8", 4), ("6/8", 2), ("5/4", 5), (None, 4)])
def test_beats_per_bar(metre, beats):
    assert billboard.beats_per_bar(metre) == beats


def make_choco(tmp_path, songs):
    root = tmp_path / "partitions" / "billboard"
    (root / "choco" / "jams").mkdir(parents=True)
    rows = ["id,billboard_id,track_title,track_performer,file_path,jams_path"]
    for i, (bid, title, artist, chords) in enumerate(songs):
        (root / "raw" / "original" / bid).mkdir(parents=True)
        (root / "raw" / "original" / bid / "salami_chords.txt").write_text(SALAMI)
        jam = {"file_metadata": {"title": title, "duration": 31.0},
               "annotations": [{"namespace": "chord", "data": [
                   {"time": s, "duration": e - s, "value": lab, "confidence": 1.0} for s, e, lab in chords]}]}
        (root / "choco" / "jams" / f"billboard_{i}.jams").write_text(json.dumps(jam))
        rows.append(f"billboard_{i},{bid},{title},{artist},x,../../partitions/billboard/choco/jams/billboard_{i}.jams")
    (root / "choco" / "meta.csv").write_text("\n".join(rows) + "\n")
    return tmp_path


def test_load_choco_billboard(tmp_path):
    chords = [(0.0, 1.0, "N"), (1.0, 3.0, "C:maj"), (3.0, 5.0, "C:maj"), (5.0, 7.0, "Bb:min7/b3")]
    root = make_choco(tmp_path, [("0002", "Song B", "Band", chords), ("0001", "Song A", "Band", chords)])
    tracks = billboard.load_choco_billboard(root)
    assert [t.track_id for t in tracks] == ["0001", "0002"]
    track = tracks[0]
    assert track.merged_chords() == [(0.0, 1.0, "N"), (1.0, 5.0, "C:maj"), (5.0, 7.0, "Bb:min7/b3")]
    assert track.tonic_at(5.0) == "C" and track.tonic_at(18.0) == "D"
    assert len(track.beats) == 4 * 4 + 4 * 4 + 2 * 3
    assert billboard.BillboardTrack.from_dict(json.loads(json.dumps(track.to_dict()))).merged_chords() == \
        track.merged_chords()


def test_primary_artist_normalisation():
    assert splits.primary_artist("The Beatles") == splits.primary_artist("Beatles")
    assert splits.primary_artist("David Ruffin, Jimmy Ruffin") == "david ruffin"
    assert splits.primary_artist("Simon & Garfunkel") == "simon"
    assert splits.primary_artist("Diana Ross featuring Lionel Richie") == "diana ross"


def test_splits_keep_artists_and_duplicates_together():
    items = [{"id": f"{i:04d}", "title": f"Song {i % 70}", "artist": f"Artist {i % 45}"} for i in range(300)]
    items.append({"id": "0999", "title": "Song 1", "artist": "The Artist 1"})  # same song as another row
    result = splits.make_splits(items, seed=1)
    assert sorted(sum(result.values(), [])) == sorted(i["id"] for i in items)
    where = {i: name for name, ids in result.items() for i in ids}
    by_artist = {}
    for item in items:
        by_artist.setdefault(splits.primary_artist(item["artist"]), set()).add(where[item["id"]])
    assert all(len(s) == 1 for s in by_artist.values())
    counts = {k: len(v) for k, v in result.items()}
    assert counts["train"] > counts["validation"] > 0 and counts["test"] > 0
    assert splits.make_splits(items, seed=1) == result  # deterministic


def test_committed_billboard_split_file():
    data = splits.read_split_file(splits.DEFAULT_SPLIT_FILE)
    ids = data["train"] + data["validation"] + data["test"]
    assert len(ids) == len(set(ids)) == 890
    assert (len(data["train"]), len(data["validation"]), len(data["test"])) == (712, 89, 89)
