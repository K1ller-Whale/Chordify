package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arbaba.chordifymobile.data.ExportFormat
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.music.Notation

/** The analysis screen's top bar: Back, the title, the capo chip and the more menu (notation, export, pick the file). */
@Composable
fun AnalysisTopBar(title: String, analysed: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
                   notation: Notation, onBack: () -> Unit, onCapo: (Int) -> Unit,
                   onNotation: (Notation) -> Unit, onExport: (ExportFormat) -> Unit,
                   onPickFile: () -> Unit, modifier: Modifier = Modifier) {}
