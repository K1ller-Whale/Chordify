package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.music.WebGoldens
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** A phone held sideways. */
val LANDSCAPE: DpSize = DpSize(800.dp, 360.dp)

/** A phone held upright. */
val PORTRAIT: DpSize = DpSize(360.dp, 760.dp)

/** loop.wav (C G Am F in C major), sevenths_stereo_22k.wav and waltz.wav: the engine's results
 *  from goldens/web.json (results[0..2].result). */
object Fixtures {
    private fun result(index: Int): AnalysisResult = ServerJson.decodeFromJsonElement(
        AnalysisResult.serializer(),
        WebGoldens.json.getValue("results").jsonArray[index].jsonObject.getValue("result"),
    )

    val loop: AnalysisResult by lazy { result(0) }
    val sevenths: AnalysisResult by lazy { result(1) }
    val waltz: AnalysisResult by lazy { result(2) }
}

/** setContent with ChordifyTheme, at [size] (DeviceConfigurationOverride.ForcedSize). */
@OptIn(ExperimentalTestApi::class)
fun ComposeContentTestRule.show(size: DpSize = LANDSCAPE, content: @Composable () -> Unit) {
    setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
            ChordifyTheme { content() }
        }
    }
}

/** Every node with a click action has a non-blank text or content description. */
fun ComposeContentTestRule.assertEveryControlNamed() {
    val unnamed = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes().filter { node ->
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        val description = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("")
        text.isNullOrBlank() && description.isNullOrBlank()
    }
    check(unnamed.isEmpty()) {
        "Controls without a name (no text, no content description): " +
            unnamed.joinToString("; ") { describeNode(it) }
    }
}

/** At least one node is a live region (SemanticsProperties.LiveRegion). */
fun ComposeContentTestRule.assertAnnounced() {
    val live = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
    check(live.isNotEmpty()) {
        "No live region on screen. Nodes: " + onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes().joinToString("; ") { describeNode(it) }
    }
}

/** Test tag, bounds and semantics of a node, for failure messages. */
private fun describeNode(node: SemanticsNode): String =
    "[tag=${node.config.getOrNull(SemanticsProperties.TestTag)} bounds=${node.boundsInRoot} config=${node.config}]"
