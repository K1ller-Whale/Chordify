package com.arbaba.chordifymobile.songwriter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Songwriter's question, asked of the shipped progression model through the app's engine. */
@RunWith(AndroidJUnit4::class)
class SongwriterDeviceTest {
    private val engine = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ChordifyApp).container.engine

    @Test
    fun theWebsFirstProgressionGetsFiveSuggestionsInAnEstimatedKey() = runBlocking<Unit> {
        val response = engine.concurrent().nextChords(listOf("C", "G", "Am"), null, SUGGESTIONS)
        assertTrue(response.keyUsed.estimated)
        assertEquals("C major", "${response.keyUsed.tonic} ${response.keyUsed.mode}")
        assertEquals(SUGGESTIONS, response.predictions.size)
        assertTrue(keyLine(response), keyLine(response).startsWith("Assuming C major ("))
        assertTrue(response.predictions.all { it.reason.isNotBlank() })
    }

    @Test
    fun aChosenKeyIsUsedAndANonsenseChordIsRefused() = runBlocking<Unit> {
        val engine = engine.concurrent()
        val response = engine.nextChords(listOf("Am", "F"), "Am", SUGGESTIONS)
        assertFalse(response.keyUsed.estimated)
        assertEquals("In A minor", keyLine(response))
        val error = assertThrows(Exception::class.java) { engine.nextChords(listOf("Hello"), null, SUGGESTIONS) }
        assertTrue(error.message.orEmpty().isNotBlank())
    }
}
