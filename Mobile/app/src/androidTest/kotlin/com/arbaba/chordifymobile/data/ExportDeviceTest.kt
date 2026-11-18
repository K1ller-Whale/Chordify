package com.arbaba.chordifymobile.data

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ServerJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.GZIPInputStream

/** An export is written to the cache and handed to the share sheet through the FileProvider. */
@RunWith(AndroidJUnit4::class)
class ExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val loop: AnalysisResult by lazy {
        val stream = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("goldens/analysis.json.gz"))
        val golden = ServerJson.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
        val input = golden.getValue("inputs").jsonArray.map { it.jsonObject }.single { it.getValue("file").jsonPrimitive.content == "loop.wav" }
        ServerJson.decodeFromJsonElement(AnalysisResult.serializer(), input.getValue("result"))
    }

    @Test
    fun theExportIsSharedAsAFile() {
        for (format in ExportFormat.entries) {
            val file = exportFile(context, "loop.wav", loop, format)
            assertEquals("loop.${format.extension}", file.name)
            val chooser = shareIntent(context, file, format)
            val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
            assertEquals(format.mimeType, send.type)
            val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
            assertEquals("content", uri.scheme)
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
            assertEquals(if (format == ExportFormat.LAB) labText(loop) else jsonText(loop), text)
        }
    }
}
