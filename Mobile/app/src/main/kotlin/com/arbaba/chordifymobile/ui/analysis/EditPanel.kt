package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation

/** The panel under the strip while editing: the selected chord's times and its actions. [chord] is the
 *  chord as shown (with the capo); times are seconds. [index] of [count] is its place in the song. */
@Composable
fun EditPanel(chord: ChordSegment, index: Int, count: Int, notation: Notation, beats: List<Double>,
              time: () -> Double, onSetStart: (Double) -> Unit, onSetEnd: (Double) -> Unit, onSplit: () -> Unit,
              onMergeWithNext: () -> Unit, onChangeChord: () -> Unit, onEditShape: () -> Unit,
              modifier: Modifier = Modifier) {}
