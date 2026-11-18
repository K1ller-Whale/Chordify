package com.arbaba.chordifymobile.quick

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.ChordifyApp
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.audio.AudioLimits
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.record.WavWriter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.sin

/** Quick chord through the app: a clip is decoded as the analysis decodes a song, and the engine,
 *  reached beside any running analysis, names it as the server's single_chord does. */
@RunWith(AndroidJUnit4::class)
class QuickChordDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = (context.applicationContext as ChordifyApp).container.engine

    private fun golden(name: String): File {
        val file = File(context.cacheDir, "quick-$name")
        javaClass.classLoader!!.getResourceAsStream("goldens/audio/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    @Test
    fun eachClipIsNamedAsTheServerNamesIt() = runBlocking<Unit> {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val clips = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject.getValue("quick_chord").jsonArray
        assertTrue(clips.isNotEmpty())
        val engine = engine.concurrent()
        for (element in clips) {
            val clip = element.jsonObject
            val name = clip.getValue("file").jsonPrimitive.content
            val start = clip.getValue("start").jsonPrimitive.double
            val end = clip.getValue("end").jsonPrimitive.double
            val song = decodeClip(AudioSource.FromFile(golden(name)), AudioLimits())
            val (label, confidence) = engine.quickChord(song.copyOfRange((start * 44100).toInt(), (end * 44100).toInt()))
            assertEquals("$name at $start s", clip.getValue("label").jsonPrimitive.content, label)
            assertEquals("$name at $start s", clip.getValue("confidence").jsonPrimitive.double, confidence, 2e-3)
        }
    }

    @Test
    fun aClipShorterThanHalfASecondIsRefused() {
        val file = File(context.cacheDir, "quick-short.wav")
        WavWriter(file, 44100).use { wav ->
            wav.write(ShortArray(44100 * 3 / 10) { (8000 * sin(2 * PI * 220 * it / 44100)).toInt().toShort() }, 44100 * 3 / 10)
        }
        val error = assertThrows(EngineException::class.java) { decodeClip(AudioSource.FromFile(file)) }
        assertEquals(ErrorCode.AUDIO_TOO_SHORT, error.code)
        file.delete()
    }
}
