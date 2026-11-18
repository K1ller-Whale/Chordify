package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.data.Analysis
import com.arbaba.chordifymobile.music.formatTime
import java.util.Locale

/** "C major · 117.5 BPM · 4:00" */
fun summary(analysis: Analysis): String = listOfNotNull(
    analysis.keyName,
    analysis.bpm?.let { String.format(Locale.ROOT, "%.1f BPM", it).replace(".0 BPM", " BPM") },
    analysis.duration?.let { formatTime(it) },
).joinToString(" · ")
