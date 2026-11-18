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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Spec §8: a file that moved can be picked again; a different file plays with a warning. */
@RunWith(AndroidJUnit4::class)
class AttachFileDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private var id: String? = null

    private fun golden(name: String, saveAs: String = name): File {
        val file = File(context.cacheDir, saveAs)
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @After
    fun remove() = runBlocking<Unit> { id?.let { library.delete(it) } }

    @Test
    fun aMovedFileIsMissingUntilPickedAgain() = runBlocking<Unit> {
        val original = golden("loop.wav", "attach-loop.wav")
        val analysis = library.import(Uri.fromFile(original), AnalysisOptions()).also { id = it }
        withTimeout(180_000) { library.observe(analysis).first { it?.status in Status.FINISHED } }
        assertTrue(library.isReadable(library.get(analysis)!!))

        original.delete()
        assertFalse(library.isReadable(library.get(analysis)!!))

        val moved = golden("loop.wav", "attach-loop-moved.wav")
        assertTrue("the same audio is the analysed file", library.attach(analysis, Uri.fromFile(moved)))
        val row = library.get(analysis)!!
        assertEquals(Uri.fromFile(moved).toString(), row.fileUri)
        assertTrue(library.isReadable(row))

        assertFalse("other audio is not", library.attach(analysis, Uri.fromFile(golden("loop_48k.wav", "attach-other.wav"))))
        moved.delete()
    }

    @Test
    fun theCapoIsKeptWithTheAnalysis() = runBlocking<Unit> {
        val analysis = library.import(Uri.fromFile(golden("loop.wav", "capo-loop.wav")), AnalysisOptions()).also { id = it }
        library.setCapo(analysis, 3)
        assertEquals(3, library.observe(analysis).first()!!.capo)
    }
}
