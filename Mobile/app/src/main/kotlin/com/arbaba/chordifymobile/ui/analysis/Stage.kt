package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.music.Notation

/** The play-along stage: [chord] as the notation shows it, its theory and capo lines, the unsure
 *  badge, its guitar shape. A horizontal swipe calls onSwipe(+1) for the next chord (swipe to the
 *  left) or onSwipe(-1) for the previous one. [sounding] is the chord as it sounds without the capo. */
@Composable
fun Stage(chord: ChordSegment, notation: Notation, capo: Int, sounding: String?,
          onSwipe: (delta: Int) -> Unit, modifier: Modifier = Modifier) {}
