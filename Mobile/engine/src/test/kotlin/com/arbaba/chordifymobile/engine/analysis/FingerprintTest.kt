package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.Goldens
import com.arbaba.chordifymobile.engine.str
import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintTest {
    // The app reuses a stored result only for the same file, options and fingerprint, as the
    // server's cache key does (chordify_backend/app/jobs.py).
    @Test
    fun theFingerprintIsTheServers() {
        val expected = Goldens.loadGz("analysis").str("fingerprint")
        assertEquals(expected, JVM_MODELS.fingerprint)
        assertEquals(expected, modelFingerprint(JVM_MODELS.bundle, JVM_MODELS.lmId))
    }
}
