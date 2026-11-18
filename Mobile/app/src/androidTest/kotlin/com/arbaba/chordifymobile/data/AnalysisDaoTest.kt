package com.arbaba.chordifymobile.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnalysisDaoTest {
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
                                                        LibraryDatabase::class.java).build()
    private val dao = database.analyses()

    private fun row(id: String, createdAt: Long, vocabulary: String? = null, status: String = Status.QUEUED) =
        Analysis(id, "$id.wav", "file:///$id.wav", false, "sha-$id", createdAt, status = status,
                 vocabulary = vocabulary, modelFingerprint = "fp")

    @After
    fun close() = database.close()

    @Test
    fun theNewestAnalysisComesFirst() = runBlocking {
        dao.upsert(row("a", 1))
        dao.upsert(row("b", 3))
        dao.upsert(row("c", 2))
        assertEquals(listOf("b", "c", "a"), dao.observeAll().first().map { it.id })
    }

    @Test
    fun aCompletedAnalysisIsReusedOnlyForTheSameFileModelsAndOptions() = runBlocking {
        dao.upsert(row("plain", 1, status = Status.COMPLETED))
        dao.upsert(row("simple", 2, vocabulary = "majmin", status = Status.COMPLETED).copy(sha256 = "sha-plain"))
        dao.upsert(row("failed", 3, status = Status.FAILED).copy(sha256 = "sha-plain"))
        assertEquals("plain", dao.findCompleted("sha-plain", "fp", null, true, 1)?.id)
        assertEquals("simple", dao.findCompleted("sha-plain", "fp", "majmin", true, 1)?.id)
        assertNull(dao.findCompleted("sha-plain", "other model", null, true, 1))
        assertNull(dao.findCompleted("sha-plain", "fp", null, false, 1))
        assertNull(dao.findCompleted("sha-plain", "fp", null, true, 2))
        assertNull(dao.findCompleted("sha-other", "fp", null, true, 1))
    }

    @Test
    fun aCancelledAnalysisStaysCancelledWhateverTheJobReportsLate() = runBlocking {
        dao.upsert(row("a", 1))
        dao.setProgress("a", Status.RUNNING, "decode", 0.3)
        assertEquals(Status.RUNNING, dao.get("a")!!.status)
        dao.setCancelled("a")
        dao.setProgress("a", Status.RUNNING, "acoustic", 0.6)
        dao.setFailed("a", "INTERNAL", "late")
        dao.setCompleted("a", 10.0, "C major", 120.0)
        val row = dao.get("a")!!
        assertEquals(Status.CANCELLED, row.status)
        assertEquals("decode", row.stage)
        assertNull(row.errorCode)
    }

    @Test
    fun completingAndFailingRecordWhatTheListShows() = runBlocking {
        dao.upsert(row("ok", 1))
        dao.setProgress("ok", Status.RUNNING, "analyse", 0.9)
        dao.setCompleted("ok", 240.0, "G major", 98.5)
        with(dao.get("ok")!!) {
            assertEquals(Status.COMPLETED, status)
            assertEquals("done", stage)
            assertEquals(1.0, progress, 0.0)
            assertEquals("G major", keyName)
            assertEquals(98.5, bpm!!, 0.0)
        }
        dao.upsert(row("bad", 2))
        dao.setProgress("bad", Status.RUNNING, "decode", 0.1)
        dao.setFailed("bad", "DECODE_FAILED", "The file contains no audio samples.")
        with(dao.get("bad")!!) {
            assertEquals(Status.FAILED, status)
            assertEquals("DECODE_FAILED", errorCode)
            assertEquals("The file contains no audio samples.", errorMessage)
        }
        dao.delete("bad")
        assertNull(dao.get("bad"))
    }
}
