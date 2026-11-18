package com.arbaba.chordifymobile.engine.acoustic

import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.NNLS_BOTHCHROMA
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class AcousticGoldenTest {
    private val golden = Goldens.loadGz("acoustic")

    private fun repoFile(path: String): File {
        val start = File("").absoluteFile // the working directory: Gradle runs unit tests in the module
        var dir: File? = start
        while (dir != null && !File(dir, path).exists()) dir = dir.parentFile
        return File(dir ?: error("$path not found above $start"), path)
    }

    private val bundleJson get() = repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText()

    @Test
    fun windowingMatchesPython() {
        for (case in golden.objects("windowed")) {
            val n = case.int("frames")!!
            // the stand-in model of mobile_goldens.acoustic_golden: input + position in the window / 1000
            val out = windowed(n, 512, 64) { start, length ->
                mapOf("a" to Array(length) { floatArrayOf((start + it).toFloat() + it.toFloat() / 1000f) })
            }.getValue("a")
            assertArrayEquals("$n frames", case.getValue("out").floats(), FloatArray(n) { out[it][0] }, 0f)
        }
    }

    @Test
    fun softmaxAndSigmoidMatchNumpy() {
        val softmax = golden.obj("softmax")
        assertMatrixClose("softmax", softmax.getValue("p").floatMatrix(), softmaxRows(softmax.getValue("logits").floatMatrix()), 1e-6)
        val sigmoid = golden.obj("sigmoid")
        assertArrayEquals(sigmoid.getValue("p").floats(), sigmoid(sigmoid.getValue("logits").floats()), 1e-6f)
    }

    @Test
    fun theBundleIsReadAsPythonReadsIt() {
        val bundle = ChordBundle.parse(bundleJson)
        assertEquals("chordnet-chroma@3.0.0", bundle.id)
        assertEquals("model.onnx", bundle.modelFile)
        assertEquals(NNLS_BOTHCHROMA, bundle.featureSpec)
        assertEquals("large", bundle.vocabulary)
        assertEquals(listOf("chord", "root", "bass", "tones", "key_tonic", "key_mode", "boundary"), bundle.outputs)
        assertEquals(512, bundle.window)
        assertEquals(64, bundle.margin)
        assertEquals(0.3, bundle.decoder.getValue("alpha"), 0.0)
        assertEquals(0.5, bundle.decoder.getValue("inversion_threshold"), 0.0)
        assertEquals(169, bundle.classPrior!!.size)
        assertEquals(sha256(repoFile("models/chordnet-chroma/3.0.0/model.onnx").readBytes()), bundle.sha256)
    }

    @Test
    fun aModelForOtherFeaturesIsRefused() {
        val other = bundleJson.replace("\"revision\": 1", "\"revision\": 2")
        val error = assertThrows(EngineException::class.java) { ChordBundle.parse(other) }
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, error.code)
    }

    @Test
    fun sha256IsLowerCaseHex() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".toByteArray()))
    }
}
