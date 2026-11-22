package com.arbaba.chordifymobile

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.arbaba.chordifymobile.data.Library
import com.arbaba.chordifymobile.data.LibraryDatabase
import com.arbaba.chordifymobile.data.Settings
import com.arbaba.chordifymobile.edit.rebuildWith
import com.arbaba.chordifymobile.engine.ChordifyEngine
import com.arbaba.chordifymobile.engine.acoustic.ChordModel
import com.arbaba.chordifymobile.engine.analysis.modelFingerprint
import com.arbaba.chordifymobile.engine.analysis.modelIds
import com.arbaba.chordifymobile.work.AnalysisNotification
import com.arbaba.chordifymobile.work.EngineHolder
import com.arbaba.chordifymobile.work.WorkJobScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** Work that must outlive a screen, on the main thread: importing a shared or opened file,
     *  which a relaunch of the activity must not cancel. Lives as long as the process. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // The same readers the engine loads its models with (ChordifyEngine.modelsFromAssets).
    private val chordBundle by lazy { ChordModel.bundleFromAssets(app) }
    private val lmId by lazy { ChordifyEngine.lmBundleFromAssets(app).first }

    /** The shipped models' fingerprint, read from their bundle.json files without loading them. */
    val fingerprint: String by lazy { modelFingerprint(chordBundle, lmId) }

    /** The shipped models, as a result's "models" block names them (About). */
    val modelIds: Map<String, String> by lazy { modelIds(chordBundle, lmId) }

    val library: Library by lazy {
        Library(app, database.analyses(), WorkJobScheduler(app), fingerprint,
            rebuild = { result, spans -> rebuildWith(engine, result, spans) })
    }
}
