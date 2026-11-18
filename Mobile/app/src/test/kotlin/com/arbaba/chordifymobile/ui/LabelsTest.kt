package com.arbaba.chordifymobile.ui

import com.arbaba.chordifymobile.engine.analysis.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LabelsTest {
    @Test
    fun everyEngineStageHasItsOwnLabel() {
        val labels = Stage.entries.map { stageLabel(it.id) }
        for ((stage, label) in Stage.entries.zip(labels)) assertNotEquals("${stage.id} has no label", "Analysing…", label)
        assertEquals(labels.size, labels.toSet().size)
        assertEquals("Waiting to start…", stageLabel(null))
    }
}
