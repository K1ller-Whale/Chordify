package com.arbaba.chordifymobile.playback

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The waveform of an analysed file: made from one decoding pass, then read from the library. */
@RunWith(AndroidJUnit4::class)
class PeaksDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val library = (context.applicationContext as ChordifyApp).container.library
    private var id: String? = null

    @After
    fun remove() = runBlocking<Unit> { id?.let { library.delete(it) } }

    @Test
    fun theWaveformIsMadeOnceAndKept() = runBlocking<Unit> {
        val file = File(context.cacheDir, "peaks-loop.wav")
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/loop.wav")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val analysis = library.import(Uri.fromFile(file), AnalysisOptions()).also { id = it }
        val row = library.get(analysis)!!

        val peaks = library.peaks(row, 8.0)!!
        assertEquals("8 s in steps of 0.05 s", 160, peaks.size)
        assertEquals(1f, peaks.max(), 0f)
        assertTrue("the loop is not silent", peaks.count { it > 0.1f } > 100)
        assertTrue(library.peaksFile(analysis).exists())
        assertArrayEquals(peaks, library.peaks(row, 8.0), 0f)

        library.delete(analysis)
        id = null
        assertTrue(!library.peaksFile(analysis).exists())
        file.delete()
        assertNull("a file that cannot be read has no waveform", library.peaks(row, 8.0))
    }
}
