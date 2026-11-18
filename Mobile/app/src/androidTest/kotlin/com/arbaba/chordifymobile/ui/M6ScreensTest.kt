package com.arbaba.chordifymobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.lm.Prediction
import com.arbaba.chordifymobile.engine.model.ProgressionKey
import com.arbaba.chordifymobile.engine.model.ProgressionResponse
import com.arbaba.chordifymobile.quick.QuickChordContent
import com.arbaba.chordifymobile.quick.QuickResult
import com.arbaba.chordifymobile.quick.QuickState
import com.arbaba.chordifymobile.songwriter.SongwriterContent
import com.arbaba.chordifymobile.ui.settings.AboutContent
import com.arbaba.chordifymobile.ui.settings.DATASET_CREDITS
import com.arbaba.chordifymobile.ui.settings.NOTICES
import com.arbaba.chordifymobile.ui.settings.SettingsContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Quick chord, the Songwriter, Settings and About in each of their states. */
@RunWith(AndroidJUnit4::class)
class M6ScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = ArrayList<String>()

    private fun quick(state: QuickState, result: QuickResult? = null, error: String? = null, settings: Boolean = false) =
        compose.setContent {
            ChordifyTheme {
                QuickChordContent(state, result, error, onRecord = { events += "record" }, onPick = { events += "pick" },
                                  onOpenAppSettings = if (settings) ({ events += "settings" }) else null)
            }
        }

    @Test
    fun quickChordWaitsForAClip() {
        quick(QuickState.Idle)
        compose.onNodeWithText("Your chord will appear here.").assertIsDisplayed()
        compose.onNodeWithText("Record a chord").performClick()
        compose.onNodeWithText("Or pick a short clip").performClick()
        assertEquals(listOf("record", "pick"), events)
    }

    @Test
    fun quickChordShowsTheLevelWhileRecording() {
        quick(QuickState.Recording(1.24, 0.1f))
        compose.onNodeWithText("Stop").assertIsDisplayed()
        compose.onNodeWithText("1.2 s").assertIsDisplayed()
        compose.onNodeWithContentDescription("Input level").assertIsDisplayed()
        compose.onNodeWithText("Or pick a short clip").assertIsNotEnabled()
    }

    @Test
    fun quickChordNamesTheChordInFull() {
        quick(QuickState.Idle, QuickResult("A:min7", "Am7", 0.8712))
        compose.onNodeWithTag("quickName").assertTextEquals("Am7")
        compose.onNodeWithText("Confidence 87%").assertIsDisplayed()
        compose.onNodeWithContentDescription("Guitar chord diagram", substring = true).assertIsDisplayed()
    }

    @Test
    fun quickChordIsBusyWhileListeningAndSaysWhatWentWrong() {
        quick(QuickState.Working, error = "Chordify needs the microphone to record.", settings = true)
        compose.onNodeWithText("Listening…").assertIsNotEnabled()
        compose.onNodeWithText("Chordify needs the microphone to record.").assertIsDisplayed()
        compose.onNodeWithText("Open the app's settings").performClick()
        assertEquals(listOf("settings"), events)
    }

    private val answer = ProgressionResponse(
        ProgressionKey("C", "major", 0.8712, estimated = true),
        listOf(Prediction("F:maj", "F", "IV", 0.4, "IV often follows vi"), Prediction("G:maj", "G", "V", 0.3, "V leads home")),
        emptyList(), "progression-ngram@1.0.0")

    private fun songwriter(answer: ProgressionResponse?, error: String? = null) = compose.setContent {
        var draft by remember { mutableStateOf("") }
        ChordifyTheme {
            SongwriterContent(listOf("C", "G", "Am"), draft, "", answer, error,
                              onRemove = { events += "remove $it" }, onDraft = { draft = it; events += "draft $it" },
                              onAdd = { events += "add" },
                              onKey = { events += "key $it" }, onSuggest = { events += "suggest" }, onPick = { events += "pick $it" })
        }
    }

    @Test
    fun theSongwriterEditsTheProgression() {
        songwriter(null)
        compose.onNodeWithText("Suggestions appear here.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove G").performClick()
        compose.onNodeWithTag("draft").performTextInput("F")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithTag("key").performClick()
        compose.onNodeWithText("Am").performClick()
        compose.onNodeWithText("Suggest the next chord").performClick()
        assertEquals(listOf("remove 1", "draft F", "add", "key Am", "suggest"), events)
    }

    @Test
    fun theSongwriterShowsWhatCouldComeNext() {
        songwriter(answer, error = "Unknown chord 'Hm'.")
        compose.onNodeWithText("Assuming C major (87% sure)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("IV often follows vi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("40%").assertIsDisplayed()
        compose.onNodeWithText("Unknown chord 'Hm'.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add F").performScrollTo().performClick()
        assertEquals(listOf("pick F"), events)
    }

    @Test
    fun settingsChangeTheDefaultAndOpenAbout() {
        compose.setContent {
            ChordifyTheme {
                SettingsContent(false, onMajorMinorOnly = { events += "majorMinorOnly $it" }, storage = "2 analyses · 1.5 MB of results",
                                onAbout = { events += "about" })
            }
        }
        compose.onNodeWithTag("majorMinorOnly").assertIsOff().performClick()
        compose.onNodeWithText("2 analyses · 1.5 MB of results").assertIsDisplayed()
        compose.onNodeWithTag("about").performClick()
        assertEquals(listOf("majorMinorOnly true", "about"), events)
    }

    @Test
    fun aboutGivesTheLicenceTheSourceAndTheCredits() {
        compose.setContent {
            ChordifyTheme {
                AboutContent("2.0.0", mapOf("chord" to "chordnet-chroma@3.0.0", "lm" to "progression-ngram@1.0.0"),
                             onSource = { events += "source" })
            }
        }
        compose.onNodeWithText("Version 2.0.0").assertIsDisplayed()
        compose.onNodeWithText("GPL-3.0-or-later", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Source code").performClick()
        for (notice in NOTICES) compose.onNodeWithText("${notice.name} · ${notice.licence}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(DATASET_CREDITS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("chord: chordnet-chroma@3.0.0").performScrollTo().assertIsDisplayed()
        assertEquals(listOf("source"), events)
    }
}
