package com.arbaba.chordifymobile.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class TimeStampedChordResponse {
    @SerializedName("segments")
    private List<TimeStampedChordSegment> segments;

    public List<TimeStampedChordSegment> getSegments() {
        return segments;
    }

    public void setSegments(List<TimeStampedChordSegment> segments) {
        this.segments = segments;
    }
}
