package com.arbaba.chordifymobile.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

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

/** The result document, as the server's .json export. */
fun jsonText(result: AnalysisResult): String = result.toJson()

/** "My song.lab" for "My song.mp3". */
fun exportName(displayName: String, format: ExportFormat): String {
    val base = displayName.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "analysis" }
    return "$base.${format.extension}"
}

/** Writes the export into the app's cache, where the share sheet can read it. */
fun exportFile(context: Context, displayName: String, result: AnalysisResult, format: ExportFormat): File {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, exportName(displayName, format))
    file.writeText(if (format == ExportFormat.LAB) labText(result) else jsonText(result))
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
