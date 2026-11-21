package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Nothing when audio == PLAYABLE (or CHECKING) and !mismatch; otherwise the slim banner. */
@Composable
fun NoAudioBanner(audio: AudioFile, mismatch: Boolean, onPickFile: () -> Unit, modifier: Modifier = Modifier) {}
