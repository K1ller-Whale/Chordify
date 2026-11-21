package com.arbaba.chordifymobile.data

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenOrientationTest {
    @Test
    fun mapsToTheActivityOrientations() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, ScreenOrientation.LANDSCAPE.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT, ScreenOrientation.PORTRAIT.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, ScreenOrientation.FOLLOW_PHONE.activityOrientation)
    }

    @Test
    fun labelsAreInPlainEnglish() {
        assertEquals("Landscape", ScreenOrientation.LANDSCAPE.label)
        assertEquals("Portrait", ScreenOrientation.PORTRAIT.label)
        assertEquals("Follow the phone", ScreenOrientation.FOLLOW_PHONE.label)
    }
}
