package com.arbaba.chordifymobile.engine.analysis

import com.arbaba.chordifymobile.engine.acoustic.ChordBundle
import com.arbaba.chordifymobile.engine.lm.NgramProgressionModel
import java.io.File

/** A file of the repository, found from the module directory Gradle runs unit tests in. */
fun repoFile(path: String): File {
    val start = File("").absoluteFile
    return generateSequence(start) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }
        ?: error("$path not found above $start")
}

/** The shipped models, read from the repository's models/ (no ONNX session: the JVM tests start
 *  from the chord model's recorded output). */
val JVM_MODELS: Models by lazy {
    val bundle = ChordBundle.parse(repoFile("models/chordnet-chroma/3.0.0/bundle.json").readText())
    val (lmId, lmFile) = Models.readLmBundle(repoFile("${Models.LM_BUNDLE}/bundle.json").readText())
    Models(bundle, repoFile("${Models.LM_BUNDLE}/$lmFile").inputStream().use { NgramProgressionModel.load(it) }, lmId)
}
