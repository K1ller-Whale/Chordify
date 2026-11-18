package com.arbaba.chordifymobile.engine.acoustic

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.EngineException
import com.arbaba.chordifymobile.engine.ErrorCode
import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.assertMatrixClose
import com.arbaba.chordifymobile.engine.features.normaliseBothchroma
import com.arbaba.chordifymobile.engine.floatMatrix
import com.arbaba.chordifymobile.engine.floats
import com.arbaba.chordifymobile.engine.int
import com.arbaba.chordifymobile.engine.obj
import com.arbaba.chordifymobile.engine.objects
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChordModelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val loopFeatures = Goldens.loadGz("features").objects("inputs")
        .single { it.str("file") == "loop.wav" }.getValue("raw").floatMatrix()

    private fun tiled(tiles: Int) = Array(loopFeatures.size * tiles) { loopFeatures[it % loopFeatures.size] }

    // spec §7.1: model outputs (logits) within 1e-3 of Python's, for one window and for several
    @Test
    fun logitsMatchPython() {
        ChordModel.fromAssets(context).use { model ->
            for (case in Goldens.loadGz("acoustic").objects("cases")) {
                val name = case.str("case")!!
                val logits = model.logits(normaliseBothchroma(tiled(case.int("tiles")!!)))
                val expected = case.obj("logits")
                for ((head, rows) in logits) {
                    val want = if (head == "boundary") expected.getValue(head).floats().map { floatArrayOf(it) }.toTypedArray()
                    else expected.getValue(head).floatMatrix()
                    assertMatrixClose("$name $head", want, rows, 1e-3)
                }
            }
        }
    }

    @Test
    fun predictGivesProbabilities() {
        ChordModel.fromAssets(context).use { model ->
            val output = model.predict(loopFeatures)
            assertEquals(loopFeatures.size, output.chord.size)
            assertEquals(169, output.chord[0].size)
            for (row in output.chord + output.keyTonic + output.keyMode + output.bass) assertEquals(1.0, row.sum().toDouble(), 1e-4)
            for (p in output.boundary) assertEquals(true, p in 0f..1f)
        }
    }

    @Test
    fun aDamagedModelIsRefused() {
        val bundle = ChordBundle.parse(context.assets.open("${ChordModel.BUNDLE}/bundle.json").use { it.readBytes().decodeToString() })
        val bytes = context.assets.open("${ChordModel.BUNDLE}/${bundle.modelFile}").use { it.readBytes() }
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        val error = assertThrows(EngineException::class.java) { ChordModel(bundle, bytes) }
        assertEquals(ErrorCode.MODEL_UNAVAILABLE, error.code)
    }
}
