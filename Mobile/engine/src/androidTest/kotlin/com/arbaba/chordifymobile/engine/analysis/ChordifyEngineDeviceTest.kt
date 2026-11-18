package com.arbaba.chordifymobile.engine.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.CancelledException
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.TestAudio
import com.arbaba.chordifymobile.engine.assertJsonClose
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.double
import com.arbaba.chordifymobile.engine.has
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.sourceInfo
import com.arbaba.chordifymobile.engine.str
import kotlinx.serialization.json.Json
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordifyEngineDeviceTest {
    private val golden = Goldens.loadGz("analysis")
    private val analysisId = golden.str("analysis_id")!!

    // M3's "done when" (spec §9): the whole AnalysisResult, from the audio file, matches Python's.
    // Labels, keys and structure are identical; numbers agree to DEVICE_TOLERANCE, the room the
    // model's logits have (1e-3) once they are probabilities rounded to 4 places.
    @Test
    fun analysesMatchTheServer() {
        for (input in golden.objects("inputs")) {
            val name = input.str("file")!!
            val source = AudioSource.FromFile(TestAudio.golden(name))
            if (input.has("error")) {
                val error = assertThrows(EngineException::class.java) { engine.analyse(source, input.sourceInfo(analysisId)) }
                assertEquals(name, ErrorCode.NO_MUSIC_DETECTED, error.code)
                continue
            }
            val result = engine.analyse(source, input.sourceInfo(analysisId))
            assertJsonClose(name, input.getValue("result"), Json.parseToJsonElement(result.toJson()), DEVICE_TOLERANCE)
        }
    }

    @Test
    fun progressGoesThroughTheStagesInOrder() {
        val seen = ArrayList<Pair<Stage, Double>>()
        engine.analyse(AudioSource.FromFile(TestAudio.golden("pulse.wav")), SourceInfo("an_progress"),
                       onProgress = { stage, fraction -> seen += stage to fraction })
        assertEquals(Stage.entries.toList(), seen.map { it.first }.distinct())
        assertTrue("fractions only grow: $seen", seen.zipWithNext().all { (a, b) -> b.second >= a.second })
        assertTrue("decoding reports its progress: $seen", seen.count { it.first == Stage.DECODE } > 10)
        assertEquals(Stage.DONE to 1.0, seen.last())
    }

    @Test
    fun cancellingStopsTheDecoding() {
        var calls = 0
        assertThrows(CancelledException::class.java) {
            engine.analyse(AudioSource.FromFile(TestAudio.golden("pulse.wav")), SourceInfo("an_cancel"),
                           isCancelled = { ++calls > 20 })
        }
    }

    @Test
    fun quickChordNamesEachClipAsTheServer() {
        for (clip in golden.objects("quick_chord")) {
            val wav = TestAudio.readWav(TestAudio.golden(clip.str("file")!!))
            assertEquals(44100, wav.sampleRate)
            val pcm = wav.mono.copyOfRange((clip.double("start") * 44100).toInt(), (clip.double("end") * 44100).toInt())
            val (label, confidence) = engine.quickChord(pcm)
            val name = "${clip.str("file")} at ${clip.double("start")} s"
            assertEquals(name, clip.str("label"), label)
            assertEquals(name, clip.double("confidence"), confidence, DEVICE_TOLERANCE)
        }
    }

    @Test
    fun nextChordsUsesTheShippedProgressionModel() {
        val response = engine.nextChords(listOf("C", "G", "Am", "F"))
        assertEquals("progression-ngram@1.0.0", response.model)
        assertEquals(listOf("G", "C", "Dm"), response.predictions.map { it.display })
    }

    companion object {
        const val DEVICE_TOLERANCE = 2e-3

        private lateinit var engine: ChordifyEngine

        @BeforeClass
        @JvmStatic
        fun load() {
            engine = ChordifyEngine.fromAssets(InstrumentationRegistry.getInstrumentation().targetContext)
        }

        @AfterClass
        @JvmStatic
        fun close() = engine.close()
    }
}
