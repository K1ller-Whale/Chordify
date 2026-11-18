package com.arbaba.chordifymobile.data

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** M4's "done when" (spec §9): a song is analysed by a background job and appears in the library.
 *  Runs the app's own library, database, WorkManager and engine on the engine's golden audio. */
@RunWith(AndroidJUnit4::class)
class LibraryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private val added = ArrayList<String>()

    private fun golden(name: String): Uri {
        val file = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return Uri.fromFile(file)
    }

    private suspend fun import(name: String, options: AnalysisOptions = AnalysisOptions()) =
        library.import(golden(name), options).also { added += it }

    private suspend fun finished(id: String): Analysis = withTimeout(180_000) {
        library.analyses.first { rows -> rows.single { it.id == id }.status in Status.FINISHED }.single { it.id == id }
    }

    @After
    fun removeWhatTheTestAdded() = runBlocking { for (id in added) library.delete(id) }

    @Test
    fun aSongIsAnalysedAndAppearsInTheLibrary() = runBlocking {
        val id = import("loop.wav")
        assertEquals("loop.wav", library.get(id)!!.displayName)
        val row = finished(id)
        assertEquals(Status.COMPLETED, row.status)
        assertEquals("C major", row.keyName)
        assertEquals(117.5, row.bpm!!, 0.0)
        assertEquals(8.0, row.duration!!, 0.0)
        val result = library.result(id)!!
        assertEquals(id, result.analysisId)
        assertEquals(listOf("C", "G", "Am", "F"), result.chords.map { it.display })
        assertEquals(row.sha256, result.source.sha256)
    }

    @Test
    fun theSameFileWithTheSameOptionsReusesTheResult() = runBlocking {
        val first = import("loop.wav")
        finished(first)
        assertEquals(first, import("loop.wav"))
        val simple = import("loop.wav", AnalysisOptions(vocabulary = "majmin"))
        assertTrue(simple != first)
        assertEquals("majmin", finished(simple).vocabulary)
        assertEquals(1, library.analyses.first().count { it.id == first })
    }

    @Test
    fun silenceFailsWithTheServersMessage() = runBlocking {
        val row = finished(import("silence.wav"))
        assertEquals(Status.FAILED, row.status)
        assertEquals("NO_MUSIC_DETECTED", row.errorCode)
        assertEquals("101% of the audio has no recognisable chord.", row.errorMessage)
        assertNull(library.result(row.id))
    }

    @Test
    fun aCancelledAnalysisStopsAndCanBeStartedAgain() = runBlocking {
        val id = import("pulse.wav")
        library.cancel(id)
        assertEquals(Status.CANCELLED, finished(id).status)
        library.retry(id)
        assertEquals(Status.COMPLETED, finished(id).status)
    }

    @Test
    fun deletingRemovesTheRowAndTheResultButNotAFileTheAppDoesNotOwn() = runBlocking {
        val uri = golden("loop_48k.wav")
        val id = library.import(uri, AnalysisOptions())
        finished(id)
        assertTrue(library.resultFile(id).exists())
        library.delete(id)
        assertNull(library.get(id))
        assertFalse(library.resultFile(id).exists())
        assertTrue(File(uri.path!!).exists())
    }

    @Test
    fun aRecordingIsDeletedWithItsAnalysis() = runBlocking {
        val recording = File(context.filesDir, "recordings/test-recording.wav").apply { parentFile!!.mkdirs() }
        File(golden("loop.wav").path!!).copyTo(recording, overwrite = true)
        val id = library.addRecording(recording, "Recording", AnalysisOptions()).also { added += it }
        assertTrue(library.get(id)!!.ownsFile)
        library.delete(id)
        assertFalse(recording.exists())
    }
}
