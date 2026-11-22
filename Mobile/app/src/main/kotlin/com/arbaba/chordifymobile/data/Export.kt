package com.arbaba.chordifymobile.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.ChordSegment
import com.arbaba.chordifymobile.engine.model.ServerJson
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

// Export as the server's GET /api/v2/analyses/{id}/export (chordify_backend/app/api/analyses.py).

enum class ExportFormat(val extension: String, val mimeType: String) {
    LAB("lab", "text/plain"),
    JSON("json", "application/json"),
}

/** Python's f"{x:.3f}": the exact binary value rounded half to even. */
fun fixed3(x: Double): String = BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).toPlainString()

/** One "start\tend\tlabel" line per chord, as the server's .lab export. */
fun labText(result: AnalysisResult): String = buildString {
    for (chord in result.chords) append(fixed3(chord.start)).append('\t').append(fixed3(chord.end)).append('\t').append(chord.label).append('\n')
}

/** What an export carries of the user's edits: the model's chords and the custom shapes. */
data class ExportEdits(val originalChords: List<ChordSegment>, val shapes: Map<String, List<Int>>)

/** An export read back: the shown result and, when the song was edited, what the model made. */
data class ParsedExport(val result: AnalysisResult, val edits: ExportEdits?)

/** What an import says when the export's edits cannot be used. */
const val EDITS_DAMAGED = "This export's edits are damaged."

/** The result document, as the server's .json export. With [edits] it gains one top-level
 *  `edits` object holding the model's chords and the custom shapes. */
fun jsonText(result: AnalysisResult, edits: ExportEdits? = null): String {
    if (edits == null) return result.toJson()
    val obj = ServerJson.parseToJsonElement(result.toJson()).jsonObject
    val extra = buildJsonObject {
        put("version", JsonPrimitive(1))
        put("original_chords", ServerJson.encodeToJsonElement(ListSerializer(ChordSegment.serializer()), edits.originalChords))
        put("shapes", JsonObject(edits.shapes.mapValues { (_, fret) -> JsonArray(fret.map { JsonPrimitive(it) }) }))
    }
    return ServerJson.encodeToString(JsonObject.serializer(), JsonObject(obj + ("edits" to extra)))
}

/** "My song.lab" for "My song.mp3". */
fun exportName(displayName: String, format: ExportFormat): String {
    val base = displayName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "analysis" }
    return "$base.${format.extension}"
}

/** What an import says when the file is not an analysis. */
const val NOT_AN_EXPORT = "This file is not an analysis exported by Chordify (.json)."

/** The analysis in a .json export, from this app, another phone or the web app; a .lab file, or
 *  anything else, is refused with [NOT_AN_EXPORT]. */
fun readExport(text: String): ParsedExport {
    val root = try {
        ServerJson.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: throw IllegalArgumentException(NOT_AN_EXPORT)
    val result = parseResult(ServerJson.encodeToString(JsonObject.serializer(), JsonObject(root - "edits")))
    val raw = root["edits"] ?: return ParsedExport(result, null)
    val edits = try {
        readEdits(raw, result)
    } catch (_: Exception) {
        throw IllegalArgumentException(EDITS_DAMAGED)
    }
    return ParsedExport(result, edits)
}

/** Just the result of [readExport]. */
fun parseExport(text: String): AnalysisResult = readExport(text).result

private fun parseResult(text: String): AnalysisResult {
    val result = try {
        parseAnalysisResult(text)
    } catch (_: Exception) {
        throw IllegalArgumentException(NOT_AN_EXPORT)
    }
    require(result.status == "completed" && result.chords.isNotEmpty()) { NOT_AN_EXPORT }
    return result
}

/** The edits object; throws on any damage. */
private fun readEdits(raw: JsonElement, result: AnalysisResult): ExportEdits {
    val obj = raw as JsonObject
    require(obj.getValue("version").jsonPrimitive.int == 1)
    val original = ServerJson.decodeFromJsonElement(ListSerializer(ChordSegment.serializer()), obj.getValue("original_chords"))
    require(original.isNotEmpty())
    require(covers(original, result.source.duration) && covers(result.chords, result.source.duration))
    val shapes = (obj["shapes"]?.jsonObject ?: JsonObject(emptyMap())).mapValues { (_, value) ->
        val frets = value.jsonArray.map { it.jsonPrimitive.int }
        require(frets.size == 6 && frets.all { it in -1..24 })
        frets
    }
    return ExportEdits(original, shapes)
}

/** Whether [chords] run from 0 to [duration] without a gap or overlap, within 1e-3. */
private fun covers(chords: List<ChordSegment>, duration: Double): Boolean =
    chords.isNotEmpty() && abs(chords.first().start) <= 1e-3 && abs(chords.last().end - duration) <= 1e-3 &&
        chords.zipWithNext().all { (a, b) -> abs(a.end - b.start) <= 1e-3 }

/** Whether a shared or picked file is a .json export rather than audio. */
fun isAnalysisExport(mimeType: String?, name: String?): Boolean =
    mimeType == ExportFormat.JSON.mimeType || name?.endsWith(".json", ignoreCase = true) == true

/** Writes the export into the app's cache, where the share sheet can read it. */
fun exportFile(context: Context, displayName: String, result: AnalysisResult, format: ExportFormat, edits: ExportEdits? = null): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, exportName(displayName, format))
    file.writeText(if (format == ExportFormat.LAB) labText(result) else jsonText(result, edits))
    return file
}

/** The share sheet for an exported [file], readable by the app the user picks. */
fun shareIntent(context: Context, file: File, format: ExportFormat): Intent {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(format.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, "Export chords")
}
