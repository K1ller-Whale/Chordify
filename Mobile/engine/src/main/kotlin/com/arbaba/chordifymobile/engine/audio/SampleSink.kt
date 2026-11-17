package com.arbaba.chordifymobile.engine.audio

/** Receives mono audio chunk by chunk while a file is decoded. */
fun interface SampleSink {
    /** The first [count] values of [samples]. The array may be reused once the call returns. */
    fun write(samples: FloatArray, count: Int)
}
