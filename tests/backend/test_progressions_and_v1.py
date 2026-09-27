import json

import pytest

from .conftest import wav_bytes


def test_next_chord_for_a_typed_progression(client):
    r = client.post("/api/v2/progressions/next", json={"chords": ["C", "G", "Am", "F", "C", "G", "Am"]})
    assert r.status_code == 200
    body = r.json()
    assert body["key_used"] == {"tonic": "C", "mode": "major", "confidence": pytest.approx(body["key_used"]["confidence"]),
                                "estimated": True}
    assert body["predictions"][0]["display"] == "F" and body["predictions"][0]["roman"] == "IV"
    assert "earlier in the song" in body["predictions"][0]["reason"]
    assert len(body["theory_only"]) == 3 and body["model"] == "progression-ngram@1.0.0"


def test_next_chord_with_a_given_key_and_harte_labels(client):
    r = client.post("/api/v2/progressions/next", json={"chords": ["D:maj", "A:7"], "key": "D major", "k": 5})
    body = r.json()
    assert body["key_used"]["estimated"] is False and body["key_used"]["tonic"] == "D"
    assert body["predictions"][0]["display"] == "D" and "authentic cadence" in body["predictions"][0]["reason"]
    assert len(body["predictions"]) == 5


@pytest.mark.parametrize("payload", [
    {"chords": ["C", "Hm"]},
    {"chords": []},
    {"chords": ["C", "G"], "durations_beats": [4]},
    {"chords": ["C"], "key": "H major"},
    {"chords": ["N.C."]},
])
def test_next_chord_validation(client, payload):
    r = client.post("/api/v2/progressions/next", json=payload)
    assert r.status_code == 422 and r.json()["code"] == "INVALID_REQUEST"


def test_v1_predict_runs_on_the_new_pipeline(client):
    r = client.post("/predict", files={"file": ("chord.wav", wav_bytes([("A:min", 3.0)], bpm=None), "audio/wav")})
    assert r.status_code == 200
    assert r.json()["chord"] == "A:min" and 0 < r.json()["confidence"] <= 100
    assert r.headers["Deprecation"] == "true" and "Sunset" in r.headers


def test_v1_predict_time_stamps(client):
    data = wav_bytes([("C:maj", 2.0), ("F:maj", 2.0), ("G:maj", 2.0)], bpm=None)
    stamps = [{"start": "0.2", "end": "1.8"}, {"start": "2.2s", "end": "3.8s"}, {"start": "4.2", "end": "5.8"}]
    r = client.post("/predict_time_stamps", files={"file": ("p.wav", data)}, data={"timestamps": json.dumps(stamps)})
    assert [s["chord"] for s in r.json()["segments"]] == ["C:maj", "F:maj", "G:maj"]
    bad = client.post("/predict_time_stamps", files={"file": ("p.wav", data)},
                      data={"timestamps": json.dumps([{"start": "3", "end": "1"}])})
    assert bad.status_code == 400


def test_v1_extract_full_chroma_returns_png_without_writing_files(client):
    r = client.post("/extract_full_chroma", files={"file": ("c.wav", wav_bytes([("E:min", 3.0)], bpm=None))})
    assert r.status_code == 200 and r.headers["content-type"] == "image/png"
    assert r.content[:8] == b"\x89PNG\r\n\x1a\n"
