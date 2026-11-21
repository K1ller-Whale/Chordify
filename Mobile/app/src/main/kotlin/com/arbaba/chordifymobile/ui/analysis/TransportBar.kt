package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.AnalysisResult

/** Play/pause, the time, the seek bar (the whole song in function colours) and the speed chip.
 *  [visibleSeconds] is how many seconds the strip shows, outlined on the seek bar. */
@Composable
fun TransportBar(result: AnalysisResult, time: State<Double>, playing: Boolean, rate: Double,
                 visibleSeconds: Double, onToggle: () -> Unit, onSeek: (Double) -> Unit,
                 onRate: (Double) -> Unit, modifier: Modifier = Modifier) {}
