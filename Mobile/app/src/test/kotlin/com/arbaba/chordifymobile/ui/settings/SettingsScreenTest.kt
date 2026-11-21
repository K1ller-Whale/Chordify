package com.arbaba.chordifymobile.ui.settings

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.data.ScreenOrientation
import com.arbaba.chordifymobile.ui.LANDSCAPE
import com.arbaba.chordifymobile.ui.PORTRAIT
import com.arbaba.chordifymobile.ui.assertEveryControlNamed
import com.arbaba.chordifymobile.ui.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val storage = "2 analyses · 1.0 MB of results, recordings and copied files"

    private fun settings(size: DpSize, onMajorMinorOnly: (Boolean) -> Unit = {}, onScreen: (ScreenOrientation) -> Unit = {},
                         onAbout: () -> Unit = {}, onBack: () -> Unit = {}) =
        rule.show(size) {
            SettingsContent(false, onMajorMinorOnly, ScreenOrientation.LANDSCAPE, onScreen, storage, onAbout, onBack)
        }

    private fun about(size: DpSize, onSource: () -> Unit = {}, onBack: () -> Unit = {}) =
        rule.show(size) { AboutContent("2.0.0", mapOf("chord" to "model-a"), onSource, onBack) }

    @Test fun switchIsOffAndCallsBack() {
        var got: Boolean? = null
        settings(LANDSCAPE, onMajorMinorOnly = { got = it })
        rule.onNodeWithTag("majorMinorOnly").assertIsOff().performClick()
        assertEquals(true, got)
    }

    @Test fun screenChoice() {
        var got: ScreenOrientation? = null
        settings(LANDSCAPE, onScreen = { got = it })
        rule.onNodeWithTag("screen-LANDSCAPE").assertIsSelected()
        rule.onNodeWithTag("screen-PORTRAIT").assertIsNotSelected()
        rule.onNodeWithText("Follow the phone").assertExists()
        rule.onNodeWithText("Portrait").performClick()
        assertEquals(ScreenOrientation.PORTRAIT, got)
    }

    @Test fun storageAboutAndBack() {
        var about = 0
        var back = 0
        settings(PORTRAIT, onAbout = { about++ }, onBack = { back++ })
        rule.onNodeWithText(storage).assertExists()
        rule.onNodeWithTag("about").performScrollTo().performClick()
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, about)
        assertEquals(1, back)
        rule.assertEveryControlNamed()
    }

    @Test fun settingsControlsNamedLandscape() {
        settings(LANDSCAPE)
        rule.assertEveryControlNamed()
    }

    @Test fun aboutShowsEverything() {
        var source = 0
        var back = 0
        about(PORTRAIT, onSource = { source++ }, onBack = { back++ })
        rule.onNodeWithText("Version 2.0.0").assertExists()
        rule.onNodeWithText("GPL-3.0-or-later", substring = true).assertExists()
        for (n in NOTICES) rule.onNodeWithText("${n.name} · ${n.licence}").assertExists()
        rule.onNodeWithText(DATASET_CREDITS).assertExists()
        rule.onNodeWithText("chord: model-a").assertExists()
        rule.onNodeWithText("Source code").performScrollTo().performClick()
        rule.onNodeWithTag("back").performClick()
        assertEquals(1, source)
        assertEquals(1, back)
        rule.assertEveryControlNamed()
    }

    @Test fun aboutControlsNamedLandscape() {
        about(LANDSCAPE)
        rule.assertEveryControlNamed()
    }
}
