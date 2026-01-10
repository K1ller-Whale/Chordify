package com.arbaba.chordifymobile.model;

import com.google.gson.annotations.SerializedName;

public class TimeStampedChordSegment {
    @SerializedName("start")
    private String start;

    @SerializedName("end")
    private String end;

    @SerializedName("chord")
    private String chord;

    @SerializedName("confidence")
    private float confidence;

    public String getStart() {
        return start;
    }

    public void setStart(String start) {
        this.start = start;
    }

    public String getEnd() {
        return end;
    }

    public void setEnd(String end) {
        this.end = end;
    }

    public String getChord() {
        return chord;
    }

    public void setChord(String chord) {
        this.chord = chord;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }
}
