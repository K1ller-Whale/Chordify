package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation

/** The Chord tab: Now (function in key, scale, countdown, doubt) and What comes next. Fills
 *  [modifier]'s space and scrolls vertically itself. */
@Composable
fun ChordPanel(chord: ChordSegment, result: AnalysisResult, time: () -> Double, notation: Notation,
               modifier: Modifier = Modifier) {}
