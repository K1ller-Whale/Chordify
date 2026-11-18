package com.arbaba.chordifymobile.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Starts and stops analysis jobs; WorkManager in the app, something simpler in tests. */
interface JobScheduler {
    fun start(id: String)
    fun cancel(id: String)
}

/** The library of analyses (spec §6.1): rows in Room, result documents as files in app storage,
 *  and the jobs that make them. Picked files are read where they are, through a persisted URI
 *  permission; a shared file the app cannot keep a permission for is copied in. */
class Library(
    private val context: Context,
    private val dao: AnalysisDao,
    private val jobs: JobScheduler,
    private val fingerprint: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val resultsDir = File(context.filesDir, "results")
    private val importsDir = File(context.filesDir, "imports")

    val analyses: Flow<List<Analysis>> = dao.observeAll()

    suspend fun get(id: String): Analysis? = dao.get(id)

    /** Adds the audio at [uri] and starts its analysis; returns the analysis id. An earlier,
     *  completed analysis of the same file with the same models and options is reused. */
    suspend fun import(uri: Uri, options: AnalysisOptions, displayName: String? = null): String = withContext(Dispatchers.IO) {
        val name = displayName ?: displayNameOf(uri)
        val sha256 = sha256(uri)
        reuse(sha256, options)?.let { return@withContext it }
        val id = newId()
        val (fileUri, owns) = keep(uri, id, name)
        add(Analysis(id, name, fileUri.toString(), owns, sha256, clock(), modelFingerprint = fingerprint,
                     vocabulary = options.vocabulary, predictions = options.predictions,
                     minSegmentBeats = options.minSegmentBeats))
        id
    }

    /** Adds one of the app's own recordings, which is deleted with its analysis. */
    suspend fun addRecording(file: File, displayName: String, options: AnalysisOptions): String = withContext(Dispatchers.IO) {
        val id = newId()
        add(Analysis(id, displayName, Uri.fromFile(file).toString(), true, sha256(Uri.fromFile(file)), clock(),
                     modelFingerprint = fingerprint, vocabulary = options.vocabulary, predictions = options.predictions,
                     minSegmentBeats = options.minSegmentBeats))
        id
    }

    /** Analyses a failed or cancelled row again, with the same file and options. */
    suspend fun retry(id: String) {
        val row = dao.get(id) ?: return
        if (row.status !in setOf(Status.FAILED, Status.CANCELLED)) return
        add(row.copy(status = Status.QUEUED, stage = null, progress = 0.0, errorCode = null, errorMessage = null))
    }

    suspend fun cancel(id: String) {
        dao.setCancelled(id)
        jobs.cancel(id)
    }

    /** Removes the row, its result and, if the app owns it, the audio file. */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: return@withContext
        jobs.cancel(id)
        dao.delete(id)
        resultFile(id).delete()
        if (row.ownsFile) Uri.parse(row.fileUri).path?.let { File(it).delete() }
    }

    suspend fun result(id: String): AnalysisResult? = withContext(Dispatchers.IO) {
        resultFile(id).takeIf { it.exists() }?.let { parseAnalysisResult(it.readText()) }
    }

    // -- what the analysis job reports ---------------------------------------------------------

    suspend fun reportProgress(id: String, stage: String, progress: Double) = dao.setProgress(id, Status.RUNNING, stage, progress)

    suspend fun reportFailed(id: String, code: String, message: String) = dao.setFailed(id, code, message)

    suspend fun reportCancelled(id: String) = dao.setCancelled(id)

    suspend fun reportCompleted(id: String, result: AnalysisResult) = withContext(Dispatchers.IO) {
        resultsDir.mkdirs()
        val file = resultFile(id)
        val partial = File(resultsDir, "$id.json.part")
        partial.writeText(result.toJson())
        partial.renameTo(file)
        val key = result.key.global
        dao.setCompleted(id, result.source.duration, "${key.tonic} ${key.mode}", result.tempo.bpm)
    }

    fun resultFile(id: String): File = File(resultsDir, "$id.json")

    // -- files ----------------------------------------------------------------------------------

    private suspend fun add(row: Analysis) {
        dao.upsert(row)
        jobs.start(row.id)
    }

    private suspend fun reuse(sha256: String, options: AnalysisOptions): String? =
        dao.findCompleted(sha256, fingerprint, options.vocabulary, options.predictions, options.minSegmentBeats)
            ?.takeIf { resultFile(it.id).exists() }?.id

    /** The URI the job will read, and whether the app owns the file it points to. */
    private fun keep(uri: Uri, id: String, name: String): Pair<Uri, Boolean> {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri to false
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri to false
        } catch (_: SecurityException) {
            // Shared or opened from another app: the read grant ends with the activity, so keep a copy.
        }
        importsDir.mkdirs()
        val copy = File(importsDir, "$id.${name.substringAfterLast('.', "audio")}")
        open(uri).use { input -> copy.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(copy) to true
    }

    private fun displayNameOf(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
    }

    private fun sha256(uri: Uri): String = open(uri).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException("Cannot read $uri")

    private fun newId() = "an_${UUID.randomUUID().toString().replace("-", "").take(20)}"
}
