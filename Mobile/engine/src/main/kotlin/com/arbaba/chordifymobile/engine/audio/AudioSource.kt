package com.arbaba.chordifymobile.engine.audio

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import java.io.File

/** A file to analyse: a path, or a content URI the user picked (read with their permission). */
sealed interface AudioSource {
    fun openIn(extractor: MediaExtractor)

    data class FromFile(val file: File) : AudioSource {
        override fun openIn(extractor: MediaExtractor) = extractor.setDataSource(file.absolutePath)
    }

    data class FromUri(val context: Context, val uri: Uri) : AudioSource {
        override fun openIn(extractor: MediaExtractor) = extractor.setDataSource(context, uri, null)
    }
}
