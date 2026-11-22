package com.arbaba.chordifymobile

import android.app.Activity
import android.app.Application
import android.app.UiAutomation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.view.Display
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbaba.chordifymobile.data.ScreenOrientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** A song opened from another app while the phone is upright: the activity must not be relaunched
 *  into landscape, and the file must reach the Library (the relaunch used to cancel its import). */
@RunWith(AndroidJUnit4::class)
class SharedFileDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ChordifyApp
    private val library = app.container.library
    private val shared = File(context.cacheDir, "exports/opened-upright.wav")
    private var added: String? = null

    @Before
    fun holdThePhoneUpright() {
        runBlocking { app.container.settings.setScreenOrientation(ScreenOrientation.LANDSCAPE) } // the default
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_FREEZE_0) // a Pixel 6 is upright at 0
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        waitUntil(5_000) { display.rotation == Surface.ROTATION_0 }
        assertEquals("the phone is upright", Surface.ROTATION_0, display.rotation)
    }

    @After
    fun cleanUp() {
        runBlocking { added?.let { library.delete(it) } }
        shared.delete()
        instrumentation.uiAutomation.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    @Test
    fun aSongOpenedWithThePhoneUprightReachesTheLibrary() {
        // A song of a realistic size, so that copying it in takes as long as it does on a phone.
        shared.parentFile!!.mkdirs()
        shared.outputStream().use { out ->
            javaClass.classLoader!!.getResourceAsStream("goldens/audio/loop.wav")!!.use { it.copyTo(out) }
            out.write(ByteArray(48 shl 20))
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shared)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClass(context, MainActivity::class.java)

        val created = AtomicInteger()
        val counter = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is MainActivity) created.incrementAndGet()
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        app.registerActivityLifecycleCallbacks(counter)
        try {
            ActivityScenario.launch<MainActivity>(open).use {
                waitUntil(30_000) { row() != null }
                added = row()?.id
                assertNotNull("the opened song is in the Library", added)
                Thread.sleep(2_000) // a relaunch into landscape would have happened by now
                assertEquals("the activity is created once, already in landscape", 1, created.get())
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(counter)
        }
    }

    private fun row() = runBlocking { library.analyses.first().firstOrNull { it.displayName == shared.name } }

    private fun waitUntil(millis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + millis
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
    }
}
