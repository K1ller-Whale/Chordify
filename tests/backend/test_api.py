import json

import pytest

from chordify_backend.app.schemas import AnalysisResult

from .conftest import wait_for, wav_bytes

LOOP = ["C:maj", "G:maj", "A:min", "F:maj"]


@pytest.fixture(scope="module")
def loop_analysis(client):
    data = wav_bytes([(c, 2.0) for c in LOOP * 4])
    response = client.post("/api/v2/analyses", files={"file": ("loop.wav", data, "audio/wav")})
    assert response.status_code == 202
    assert response.headers["Location"] == f"/api/v2/analyses/{response.json()['id']}"
    status = wait_for(client, response.json()["id"])
    assert status["status"] == "completed", status
    return data, status["id"], client.get(f"/api/v2/analyses/{status['id']}/result").json()


def test_health_and_models(client):
    assert client.get("/healthz").json() == {"status": "ok"}
    info = client.get("/api/v2/models").json()
    assert info["models"]["chord"] == "chroma-templates@0.1.0"
    assert info["models"]["lm"] == "progression-ngram@1.0.0"
    assert info["features"]["frame_rate"] == pytest.approx(44100 / 2048)


def test_result_follows_the_schema_and_finds_the_chords(loop_analysis):
    _, analysis_id, result = loop_analysis
    AnalysisResult.model_validate(result)
    assert result["analysis_id"] == analysis_id
    chords = [c for c in result["chords"] if c["label"] != "N"]
    assert [c["display"] for c in chords] == ["C", "G", "Am", "F"] * 4
    for chord, expected_start in zip(chords, range(0, 32, 2)):
        assert chord["start"] == pytest.approx(expected_start, abs=0.15)
    assert [c["roman"] for c in chords[:4]] == ["I", "V", "vi", "IV"]
    assert [c["function"] for c in chords[:4]] == ["tonic", "dominant", "tonic", "subdominant"]
    assert result["key"]["global"]["tonic"] == "C" and result["key"]["global"]["mode"] == "major"
    assert result["tempo"]["bpm"] == pytest.approx(120, rel=0.06)
    assert result["summary"]["patterns"][0]["roman"] == ["I", "V", "vi", "IV"]
    assert {s["display"] for s in result["summary"]["time_share"]} == {"C", "G", "Am", "F"}
    assert len(result["bars"]) >= 15 and result["bars"][1]["chords"]


def test_predictions_use_the_song_history(loop_analysis):
    _, _, result = loop_analysis
    chords = [c for c in result["chords"] if c["label"] != "N"]
    late_g = chords[-3]  # a G late in the song: the loop continues with Am
    assert late_g["display"] == "G"
    top = late_g["next"][0]
    assert top["display"] == "Am" and top["cache_count"] >= 2 and "earlier in the song" in top["reason"]
    assert late_g["theory_only"][0]["roman"] != "vi" or late_g["theory_only"][0]["p"] < top["p"]
    assert result["summary"]["predictability"] > 0.5


def test_same_upload_is_a_cache_hit(client, loop_analysis):
    data, analysis_id, _ = loop_analysis
    again = client.post("/api/v2/analyses", files={"file": ("renamed.wav", data, "audio/wav")})
    assert again.status_code == 200 and again.json()["id"] == analysis_id
    assert analysis_id in [a["id"] for a in client.get("/api/v2/analyses").json()]


def test_status_etag(client, loop_analysis):
    _, analysis_id, _ = loop_analysis
    first = client.get(f"/api/v2/analyses/{analysis_id}")
    assert client.get(f"/api/v2/analyses/{analysis_id}", headers={"If-None-Match": first.headers["ETag"]}).status_code == 304


def test_sse_stream_replays_progress_and_ends_with_completed(client, loop_analysis):
    _, analysis_id, _ = loop_analysis
    events = []
    with client.stream("GET", f"/api/v2/analyses/{analysis_id}/events") as response:
        assert response.headers["content-type"].startswith("text/event-stream")
        for line in response.iter_lines():
            if line.startswith("event:"):
                events.append(line.split(":", 1)[1].strip())
    assert events[0] == "snapshot" and events[-1] == "completed"
    assert "partial" in events and events.count("progress") >= 5
    resumed = []
    with client.stream("GET", f"/api/v2/analyses/{analysis_id}/events", headers={"Last-Event-ID": "3"}) as response:
        resumed = [line.split(":", 1)[1].strip() for line in response.iter_lines() if line.startswith("event:")]
    assert len(resumed) < len(events) and resumed[-1] == "completed"


def test_export_and_corrections(client, loop_analysis):
    _, analysis_id, result = loop_analysis
    lab = client.get(f"/api/v2/analyses/{analysis_id}/export?format=lab").text.splitlines()
    assert len(lab) == len(result["chords"]) and lab[0].split("\t")[2] == result["chords"][0]["label"]
    bad = client.get(f"/api/v2/analyses/{analysis_id}/export?format=pdf")
    assert bad.status_code == 422 and bad.json()["code"] == "OPTION_UNAVAILABLE"
    fix = client.post(f"/api/v2/analyses/{analysis_id}/corrections", json={"chord_index": 1, "new_label": "G7"})
    assert fix.status_code == 201 and fix.json()["new_label"] == "G:7"
    assert client.post(f"/api/v2/analyses/{analysis_id}/corrections",
                       json={"chord_index": 999, "new_label": "G"}).status_code == 422


@pytest.mark.parametrize("data, code, status", [
    (b"this is not audio at all", "UNSUPPORTED_MEDIA_TYPE", 415),
    (b"RIFF\x00\x00\x00\x00WAVE" + b"\x00" * 6 * 1024 * 1024, "PAYLOAD_TOO_LARGE", 413),
])
def test_upload_errors_are_problem_json(client, data, code, status):
    response = client.post("/api/v2/analyses", files={"file": ("x.bin", data)})
    assert response.status_code == status
    assert response.headers["content-type"] == "application/problem+json"
    assert response.json()["code"] == code


@pytest.mark.parametrize("chords, code", [
    ([("C:maj", 0.5)], "AUDIO_TOO_SHORT"),
    ([("N", 6.0)], "NO_MUSIC_DETECTED"),
])
def test_job_failures_carry_codes(client, chords, code):
    response = client.post("/api/v2/analyses", files={"file": ("x.wav", wav_bytes(chords, bpm=None), "audio/wav")})
    final = wait_for(client, response.json()["id"])
    assert final["status"] == "failed" and final["error"]["code"] == code


def test_invalid_options_and_unknown_ids(client):
    data = wav_bytes([("C:maj", 2.0)])
    r = client.post("/api/v2/analyses", files={"file": ("a.wav", data)}, data={"options": json.dumps({"vocabulary": "large"})})
    assert r.status_code == 422 and r.json()["code"] == "OPTION_UNAVAILABLE"
    r = client.post("/api/v2/analyses", files={"file": ("a.wav", data)}, data={"options": "{not json"})
    assert r.status_code == 422 and r.json()["code"] == "INVALID_REQUEST"
    assert client.get("/api/v2/analyses/an_missing").json()["code"] == "NOT_FOUND"


def test_delete(client):
    r = client.post("/api/v2/analyses", files={"file": ("d.wav", wav_bytes([("D:maj", 2.0), ("G:maj", 2.0)]))})
    analysis_id = r.json()["id"]
    wait_for(client, analysis_id)
    assert client.delete(f"/api/v2/analyses/{analysis_id}").status_code == 204
    assert client.get(f"/api/v2/analyses/{analysis_id}").status_code == 404


def test_cors_allows_only_configured_origins(client):
    ok = client.options("/api/v2/models", headers={"Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET"})
    assert ok.headers.get("access-control-allow-origin") == "http://localhost:5173"
    blocked = client.options("/api/v2/models", headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"})
    assert "access-control-allow-origin" not in blocked.headers
