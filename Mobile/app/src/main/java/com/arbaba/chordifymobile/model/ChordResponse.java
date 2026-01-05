package com.arbaba.chordifymobile.model;

import com.google.gson.annotations.SerializedName;

public class ChordResponse {
    // Matches {"chord": "Am", "confidence": 0.95}

    @SerializedName("chord")
    private String chord;

    @SerializedName("confidence")
    private float confidence;

    public String getChord() { return chord; }
    public float getConfidence() { return confidence; }
}