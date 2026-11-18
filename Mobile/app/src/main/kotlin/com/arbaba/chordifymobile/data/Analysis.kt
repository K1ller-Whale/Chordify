package com.arbaba.chordifymobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** An analysis's lifecycle, as the server names it (chordify_backend/app/schemas.py). */
object Status {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    val FINISHED = setOf(COMPLETED, FAILED, CANCELLED)
}

/** One row of the library (spec §6.1). The result itself is a file (ResultStore); the row holds
 *  what the list shows and what the job needs. */
@Entity(tableName = "analyses")
data class Analysis(
    @PrimaryKey val id: String,
    val displayName: String,
    /** content:// for a picked file, file:// for a recording or a copy the app keeps. */
    val fileUri: String,
    /** The file is the app's own (a recording, or a shared file it copied), deleted with the row. */
    val ownsFile: Boolean,
    val sha256: String,
    val createdAt: Long,
    val status: String = Status.QUEUED,
    val stage: String? = null,
    val progress: Double = 0.0,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** The options it was asked with: AnalysisOptions.vocabulary, predictions, minSegmentBeats. */
    val vocabulary: String? = null,
    val predictions: Boolean = true,
    val minSegmentBeats: Int = 1,
    val modelFingerprint: String,
    val capo: Int = 0,
    /** From the result, for the list: seconds, "C major", beats per minute. */
    val duration: Double? = null,
    val keyName: String? = null,
    val bpm: Double? = null,
)

@Dao
interface AnalysisDao {
    @Query("SELECT * FROM analyses ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Analysis>>

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun get(id: String): Analysis?

    /** A finished analysis of the same file with the same models and options, whose result can be reused. */
    @Query("""SELECT * FROM analyses WHERE sha256 = :sha256 AND modelFingerprint = :fingerprint
              AND vocabulary IS :vocabulary AND predictions = :predictions AND minSegmentBeats = :minSegmentBeats
              AND status = 'completed' ORDER BY createdAt DESC LIMIT 1""")
    suspend fun findCompleted(sha256: String, fingerprint: String, vocabulary: String?, predictions: Boolean,
                              minSegmentBeats: Int): Analysis?

    @Upsert
    suspend fun upsert(analysis: Analysis)

    // A cancelled row stays cancelled: the job may report once more before it notices.
    @Query("""UPDATE analyses SET status = :status, stage = :stage, progress = :progress
              WHERE id = :id AND status IN ('queued', 'running')""")
    suspend fun setProgress(id: String, status: String, stage: String?, progress: Double)

    @Query("""UPDATE analyses SET status = 'failed', errorCode = :code, errorMessage = :message
              WHERE id = :id AND status = 'running'""")
    suspend fun setFailed(id: String, code: String, message: String)

    @Query("""UPDATE analyses SET status = 'completed', stage = 'done', progress = 1.0, duration = :duration,
              keyName = :keyName, bpm = :bpm WHERE id = :id AND status = 'running'""")
    suspend fun setCompleted(id: String, duration: Double, keyName: String, bpm: Double?)

    @Query("UPDATE analyses SET status = 'cancelled' WHERE id = :id AND status IN ('queued', 'running')")
    suspend fun setCancelled(id: String)

    @Query("DELETE FROM analyses WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [Analysis::class], version = 1)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun analyses(): AnalysisDao
}
