package com.arbaba.chordifymobile.engine

/** Loads libchordify_engine.so (src/main/cpp) once, before the first native call. */
internal object NativeLibrary {
    init {
        System.loadLibrary("chordify_engine")
    }

    fun load() = Unit
}
