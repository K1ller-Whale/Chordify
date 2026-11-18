package com.arbaba.chordifymobile

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.arbaba.chordifymobile.data.Library
import com.arbaba.chordifymobile.data.LibraryDatabase
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.Models
import com.arbaba.chordifymobile.engine.analysis.modelFingerprint
import com.arbaba.chordifymobile.engine.analysis.modelIds
import com.arbaba.chordifymobile.work.AnalysisNotification
import com.arbaba.chordifymobile.work.EngineHolder
import com.arbaba.chordifymobile.work.WorkJobScheduler

class ChordifyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AnalysisNotification.createChannel(this)
    }
}

/** The app's long-lived parts, made once per process. */
class AppContainer(context: Context) {
    private val app = context.applicationContext
    val database: LibraryDatabase = Room.databaseBuilder(app, LibraryDatabase::class.java, "library.db").build()
    val engine = EngineHolder(app)
    val settings = Settings(app)

    private fun asset(path: String) = app.assets.open(path).use { it.readBytes().decodeToString() }
    private val chordBundle by lazy { ChordBundle.parse(asset("${ChordModel.BUNDLE}/bundle.json")) }
    private val lmId by lazy { Models.readLmBundle(asset("${Models.LM_BUNDLE}/bundle.json")).first }

    /** The shipped models' fingerprint, read from their bundle.json files without loading them. */
    val fingerprint: String by lazy { modelFingerprint(chordBundle, lmId) }

    /** The shipped models, as a result's "models" block names them (About). */
    val modelIds: Map<String, String> by lazy { modelIds(chordBundle, lmId) }

    val library: Library by lazy { Library(app, database.analyses(), WorkJobScheduler(app), fingerprint) }
}
