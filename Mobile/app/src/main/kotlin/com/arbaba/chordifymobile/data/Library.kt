package com.arbaba.chordifymobile.data

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.arbaba.chordifymobile.edit.ChordEdits
import com.arbaba.chordifymobile.edit.EditsLoad
import com.arbaba.chordifymobile.engine.analysis.AnalysisOptions
import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.audio.AudioSource
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.engine.model.parseAnalysisResult
import com.arbaba.chordifymobile.engine.model.toJson
import com.arbaba.chordifymobile.playback.decodePeaks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
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
    private val rebuild: suspend (AnalysisResult, List<ChordSpan>) -> AnalysisResult = { result, _ -> result },
) {
    private val resolver: ContentResolver = context.contentResolver
    private val resultsDir = File(context.filesDir, "results")
    private val importsDir = File(context.filesDir, "imports")

    val analyses: Flow<List<Analysis>> = dao.observeAll()

    suspend fun get(id: String): Analysis? = dao.get(id)

    fun observe(id: String): Flow<Analysis?> = dao.observe(id)

    /** The capo the analysis screen shows it with (spec §6.1). */
    suspend fun setCapo(id: String, capo: Int) = dao.setCapo(id, capo)

    /** Whether the analysed file can still be read where it was. */
    suspend fun isReadable(analysis: Analysis): Boolean = withContext(Dispatchers.IO) {
        try {
            open(Uri.parse(analysis.fileUri)).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Points the analysis at [uri] after its file went missing (spec §8). Returns whether it is
     *  the file that was analysed (the same SHA-256); a different file is kept too, with a warning. */
    suspend fun attach(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val row = dao.get(id) ?: return@withContext false
        val same = row.sha256.isEmpty() || sha256(uri) == row.sha256 // an export may not record it
        val (fileUri, owns) = keep(uri, id, displayNameOf(uri))
        if (row.ownsFile && row.fileUri != fileUri.toString()) Uri.parse(row.fileUri).path?.let { File(it).delete() }
        peaksFile(id).delete() // the waveform is the new file's
        dao.setFile(id, fileUri.toString(), owns)
        same
    }

    /** Adds the audio at [uri] and starts its analysis; returns the analysis id. An earlier,
     *  completed analysis of the same file with the same models and options is reused. */
    suspend fun import(uri: Uri, options: AnalysisOptions, displayName: String? = null): String = withContext(Dispatchers.IO) {
        val name = displayName ?: displayNameOf(uri)
        val sha256 = sha256(uri)
        reuse(sha256, options)?.let { return@withContext it }
        val id = newId()
        // Once the file is being kept, its row is written too, even if the caller is cancelled
        // meanwhile: a copy in importsDir without a row would never be deleted.
        withContext(NonCancellable) {
            val (fileUri, owns) = keep(uri, id, name)
            add(Analysis(id, name, fileUri.toString(), owns, sha256, clock(), modelFingerprint = fingerprint,
                         vocabulary = options.vocabulary, predictions = options.predictions,
                         minSegmentBeats = options.minSegmentBeats))
        }
        id
    }

    /** Adds an analysis exported as .json, on another phone or by the web app, as a completed row
     *  without its audio: the analysis screen asks for the song and checks it by the SHA-256 the
     *  export records. It is never reused for a new analysis, which the shipped models may make
     *  differently. Returns the new row's id. */
    suspend fun importAnalysis(uri: Uri): String = withContext(Dispatchers.IO) {
        val text = open(uri).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                require(out.size() <= MAX_EXPORT_BYTES) { NOT_AN_EXPORT }
            }
            out.toString(Charsets.UTF_8.name())
        }
        importAnalysisText(text, displayNameOf(uri).removeSuffix(".json"))
    }

    /** Adds the analysis in the export [text] as a completed row; [fallbackName] names it when the
     *  export has no title or filename. Returns the new row's id. */
    internal suspend fun importAnalysisText(text: String, fallbackName: String): String = withContext(Dispatchers.IO) {
        val result = parseExport(text)
        val id = newId()
        resultsDir.mkdirs()
        resultFile(id).writeText(result.toJson())
        val key = result.key.global
        val name = result.source.title ?: result.source.filename ?: fallbackName
        dao.upsert(Analysis(id, name, fileUri = "", ownsFile = false, sha256 = result.source.sha256.orEmpty(),
                            createdAt = clock(), status = Status.COMPLETED, stage = "done", progress = 1.0,
                            modelFingerprint = IMPORTED, duration = result.source.duration,
                            keyName = "${key.tonic} ${key.mode}", bpm = result.tempo.bpm))
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
        editsFile(id).delete()
        peaksFile(id).delete()
        if (row.ownsFile) Uri.parse(row.fileUri).path?.let { File(it).delete() }
    }

    suspend fun result(id: String): AnalysisResult? = withContext(Dispatchers.IO) {
        resultFile(id).takeIf { it.exists() }?.let { parseAnalysisResult(it.readText()) }
    }

    // -- what the analysis job reports ---------------------------------------------------------

    suspend fun reportProgress(id: String, stage: String, progress: Double) = dao.setProgress(id, Status.RUNNING, stage, progress)

    suspend fun reportFailed(id: String, code: String, message: String) = dao.setFailed(id, code, message)

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

    /** The user's chord edits of the analysis (spec §3.1). */
    fun editsFile(id: String): File = File(resultsDir, "$id.edits.json")

    /** The song's saved edits: none, the edits, or a file that cannot be read. */
    suspend fun edits(id: String): EditsLoad = EditsLoad.None // S1

    /** Saves [edits] atomically; throws IOException when it cannot. */
    suspend fun saveEdits(id: String, edits: ChordEdits) {} // S1

    /** Deletes the song's edits. */
    suspend fun clearEdits(id: String) {} // S1

    /** The waveform of the analysis's file (lib/audio.ts waveformPeaks), made the first time it is
     *  asked for and kept next to the result; null when the file cannot be read. */
    suspend fun peaks(analysis: Analysis, duration: Double): FloatArray? = withContext(Dispatchers.IO) {
        val file = peaksFile(analysis.id)
        if (file.exists()) return@withContext DataInputStream(file.inputStream().buffered()).use { input ->
            FloatArray(input.readInt()) { input.readFloat() }
        }
        val peaks = try {
            decodePeaks(AudioSource.FromUri(context, Uri.parse(analysis.fileUri)), duration) { !isActive }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext null
        }
        resultsDir.mkdirs()
        val partial = File(resultsDir, "${analysis.id}.peaks.part")
        DataOutputStream(partial.outputStream().buffered()).use { output ->
            output.writeInt(peaks.size)
            peaks.forEach { output.writeFloat(it) }
        }
        partial.renameTo(file)
        peaks
    }

    fun peaksFile(id: String): File = File(resultsDir, "$id.peaks")

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
        try {
            open(uri).use { input -> copy.outputStream().use { input.copyTo(it) } }
        } catch (error: Exception) {
            copy.delete() // no half copy is left behind
            throw error
        }
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

    companion object {
        /** The model fingerprint of an imported analysis, which matches no analysis the app makes. */
        const val IMPORTED = "imported"

        /** The largest .json export read: a 15-minute song's is about 2 MB. */
        const val MAX_EXPORT_BYTES = 32 shl 20
    }
}
