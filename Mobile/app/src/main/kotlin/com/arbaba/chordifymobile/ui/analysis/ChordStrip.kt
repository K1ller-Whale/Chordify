package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.Notation

/** The chords coming up, sliding toward a fixed playhead (the timeline's detail track, 72 dp tall). */
@Composable
fun ChordStrip(result: AnalysisResult, time: State<Double>, current: Int, notation: Notation,
               peaks: FloatArray?, surprising: Set<Int>, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {}
