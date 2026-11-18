package com.arbaba.chordifymobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theFourTabsOpenTheirScreens() {
        compose.onNodeWithText("Library is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Quick chord").performClick()
        compose.onNodeWithText("Quick chord is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Songwriter").performClick()
        compose.onNodeWithText("Songwriter is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings is not built yet.").assertIsDisplayed()
        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Library is not built yet.").assertIsDisplayed()
    }
}
