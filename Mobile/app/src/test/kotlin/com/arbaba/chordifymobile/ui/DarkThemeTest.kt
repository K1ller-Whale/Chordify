package com.arbaba.chordifymobile.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In the dark theme, text and icons that set no colour of their own must still be light: the
 *  phone showed the top bar's title, the Back arrow and the stage's "N.C." drawn black on black,
 *  because nothing above them provided a content colour once the Scaffold was gone. These tests
 *  read the content colour such text and icons inherit. */
@RunWith(AndroidJUnit4::class)
class DarkThemeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun theTopBarGivesItsTitleAndIconsALightColourInTheDark() {
        var inherited = Color.Unspecified
        rule.show { ChordifyTheme(dark = true) { ScreenTopBar("Chordify", onBack = {}) { inherited = LocalContentColor.current } } }
        rule.waitForIdle()
        assertTrue("the top bar's content colour is light in the dark (luminance ${inherited.luminance()})", inherited.luminance() > 0.6f)
    }

    @Test
    fun theAppBackgroundGivesPlainTextALightColourInTheDark() {
        var inherited = Color.Unspecified
        rule.show { ChordifyTheme(dark = true) { AppBackground { inherited = LocalContentColor.current } } }
        rule.waitForIdle()
        assertTrue("plain text is light on the dark background (luminance ${inherited.luminance()})", inherited.luminance() > 0.6f)
    }

    @Test
    fun theAppBackgroundGivesPlainTextADarkColourInTheLight() {
        var inherited = Color.Unspecified
        rule.show { ChordifyTheme(dark = false) { AppBackground { inherited = LocalContentColor.current } } }
        rule.waitForIdle()
        assertTrue("plain text is dark on the light background (luminance ${inherited.luminance()})", inherited.luminance() < 0.3f)
    }
}
