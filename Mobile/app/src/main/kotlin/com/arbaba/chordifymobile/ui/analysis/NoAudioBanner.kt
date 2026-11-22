package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Nothing when audio == PLAYABLE (or CHECKING) and !mismatch; otherwise the slim banner. */
@Composable
fun NoAudioBanner(audio: AudioFile, mismatch: Boolean, onPickFile: () -> Unit, modifier: Modifier = Modifier) {
    val missing = audio == AudioFile.MISSING
    if (!missing && !mismatch) return
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth().testTag("noAudio")) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mismatch) FILE_MISMATCH else FILE_MISSING, style = MaterialTheme.typography.bodySmall,
                color = if (mismatch) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (missing) TextButton(onClick = onPickFile) { Text("Pick the file") }
        }
    }
}
