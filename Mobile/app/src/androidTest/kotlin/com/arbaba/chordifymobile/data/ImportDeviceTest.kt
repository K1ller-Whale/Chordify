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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** An analysis exported on one phone and imported on another: it arrives complete, without its
 *  audio, and the song picked for it is checked against the export's SHA-256. */
@RunWith(AndroidJUnit4::class)
class ImportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private val ids = ArrayList<String>()

    private fun golden(name: String, saveAs: String): File {
        val file = File(context.cacheDir, saveAs)
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @After
    fun remove() = runBlocking<Unit> { ids.forEach { library.delete(it) } }

    @Test
    fun anExportedAnalysisIsImportedWithoutItsAudio() = runBlocking<Unit> {
        // the first phone: analyse a song and export it
        val song = golden("loop.wav", "import-loop.wav")
        val analysed = library.import(Uri.fromFile(song), AnalysisOptions()).also { ids += it }
        withTimeout(180_000) { library.observe(analysed).first { it?.status in Status.FINISHED } }
        val result = library.result(analysed)!!
        val export = exportFile(context, "import-loop.wav", result, ExportFormat.JSON)
        library.delete(analysed)
        ids.clear()

        // the second phone: import the export
        val imported = library.importAnalysis(Uri.fromFile(export)).also { ids += it }
        val row = library.get(imported)!!
        assertEquals(Status.COMPLETED, row.status)
        assertEquals("import-loop.wav", row.displayName)
        assertEquals("${result.key.global.tonic} ${result.key.global.mode}", row.keyName)
        assertEquals(result.source.duration, row.duration!!, 0.0)
        assertEquals(result, library.result(imported))
        assertFalse("the audio did not come with it", library.isReadable(row))

        // the song picked for it is checked
        assertFalse("another song is not the analysed one", library.attach(imported, Uri.fromFile(golden("loop_48k.wav", "import-other.wav"))))
        assertTrue("the analysed song is", library.attach(imported, Uri.fromFile(song)))
        assertTrue(library.isReadable(library.get(imported)!!))
    }

    @Test
    fun aLabFileIsNotAnAnalysis() = runBlocking<Unit> {
        val lab = File(context.cacheDir, "chords.lab").apply { writeText("0.000\t2.000\tC:maj\n") }
        val error = assertThrows(IllegalArgumentException::class.java) { runBlocking { library.importAnalysis(Uri.fromFile(lab)) } }
        assertEquals(NOT_AN_EXPORT, error.message)
    }
}
