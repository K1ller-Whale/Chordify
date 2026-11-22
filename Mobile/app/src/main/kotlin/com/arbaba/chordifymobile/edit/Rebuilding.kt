package com.arbaba.chordifymobile.edit

import com.arbaba.chordifymobile.engine.analysis.ChordSpan
import com.arbaba.chordifymobile.engine.analysis.rebuild
import com.arbaba.chordifymobile.engine.model.AnalysisResult
import com.arbaba.chordifymobile.work.EngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The analysis rebuilt from [spans] with the app's models, off the main thread. It needs no chord model, so
 *  the first edit does not wait for the ONNX session to load. */
suspend fun rebuildWith(engine: EngineHolder, result: AnalysisResult, spans: List<ChordSpan>): AnalysisResult =
    withContext(Dispatchers.Default) { rebuild(result, spans, engine.models()) }
