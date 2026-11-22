package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import com.arbaba.chordifymobile.engine.model.KeyInfo

/** A full-screen dialog to pick a chord. [current] and [suggestions] are shown labels; [onPick] gets a shown Harte label. */
@Composable
fun ChordPicker(current: String, suggestions: List<String>, key: KeyInfo, flats: Boolean,
                onPick: (String) -> Unit, onCancel: () -> Unit) {}
