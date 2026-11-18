package com.arbaba.chordifymobile.ui

/** What the app shows for each progress stage (the web app's words, chordify-frontend Panels.tsx). */
fun stageLabel(stage: String?): String = when (stage) {
    null, "queued" -> "Waiting to start…"
    "decode" -> "Reading the audio…"
    "acoustic" -> "Recognising chords…"
    "key" -> "Working out the key…"
    "decode_chords" -> "Lining chords up with the beat…"
    "analyse" -> "Explaining the progression…"
    "done" -> "Done"
    else -> "Analysing…"
}
