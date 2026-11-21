package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.CapoSuggestion

/** The Song tab: facts, capo text, colour legend, models, shortcuts. Fills and scrolls itself.
 *  [analysed] is the result without the capo, [result] with it. */
@Composable
fun SongPanel(analysed: AnalysisResult, result: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
              modifier: Modifier = Modifier) {}
