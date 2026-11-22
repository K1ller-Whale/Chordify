package com.arbaba.chordifymobile.work

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.analysis.Models
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class EngineHolderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shipped: Models by lazy { ChordifyEngine.modelsFromAssets(context) }

    @Test fun theShippedModelsLoadWithoutTheChordModel() {
        assertEquals("chordnet-chroma@3.0.0", shipped.bundle.id)
        assertEquals("progression-ngram@1.0.0", shipped.lmId)
        assertTrue(ChordifyEngine.lmBundleFromAssets(context).second.startsWith("model.json"))
    }

    @Test fun theModelsAreLoadedOnceWithoutTheEngine() = runBlocking {
        val loads = AtomicInteger()
        val holder = EngineHolder(context, loadModels = { loads.incrementAndGet(); shipped },
                                  loadEngine = { _, _ -> error("rebuilding and suggestions need no chord model") })
        val first = holder.models()
        assertSame(first, holder.models())
        assertEquals(1, loads.get())
    }

    @Test fun modelsLoadedForACallerThatLeftAreKeptNotLoadedAgain() = runBlocking {
        val loads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val holder = EngineHolder(context, loadModels = {
            loads.incrementAndGet()
            started.complete(Unit)
            runBlocking { gate.await() }
            shipped
        }, loadEngine = { _, _ -> error("not used") })
        // On another thread: the loader blocks until the gate opens.
        val leaving = async(Dispatchers.IO) { holder.models() }
        withTimeout(10_000) { started.await() }
        leaving.cancel() // the screen closed while the models were loading
        gate.complete(Unit)
        val kept = withTimeout(10_000) { holder.models() }
        assertSame(shipped, kept)
        assertEquals(1, loads.get())
        assertTrue(leaving.isCancelled)
    }
}
