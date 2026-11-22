package com.arbaba.chordifymobile.ui.analysis

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.music.CapoSuggestion
import com.arbaba.chordifymobile.ui.LocalChordifyColors
import com.arbaba.chordifymobile.ui.SectionTitle

/** The Song tab: facts, capo text, colour legend, models, shortcuts. Fills and scrolls itself.
 *  [analysed] is the result without the capo, [result] with it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongPanel(analysed: AnalysisResult, result: AnalysisResult, capo: Int, suggestion: CapoSuggestion,
              modifier: Modifier = Modifier) {
    val colors = LocalChordifyColors.current
    val body = MaterialTheme.typography.bodySmall
    Column(modifier.fillMaxSize().testTag("songPanel").verticalScroll(rememberScrollState()).padding(12.dp),
           verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("This song")
        Text(songDetails(analysed))

        Column(Modifier.padding(top = 8.dp)) { SectionTitle("Capo") }
        val notice = capoNotice(analysed, capo)
        val suggested = suggestionNotice(suggestion, capo)
        notice?.let { Text(it, style = body) }
        suggested?.let { Text(it, style = body) }
        if (notice == null && suggested == null) Text("No capo needed: the shapes are the song's own.", style = body)

        Column(Modifier.padding(top = 8.dp)) { SectionTitle("Colours") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((name, color) in listOf("Tonic" to colors.tonic, "Subdominant" to colors.subdominant, "Dominant" to colors.dominant)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Text(name, style = body)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(10.dp)) { hatch(colors.borrowed, 0f, 0f, size.width, size.height, 3 * density, 1f) }
                Text("Outside the key", style = body)
            }
            Text("✦ Surprising change", style = body)
        }

        Column(Modifier.padding(top = 8.dp)) { SectionTitle("Models") }
        for ((role, id) in result.models) Text("$role $id", style = body)

        Column(Modifier.padding(top = 8.dp)) { SectionTitle("Keyboard") }
        Text("Space plays or pauses, ← and → jump between chords, R toggles Roman numerals.", style = body)
    }
}
