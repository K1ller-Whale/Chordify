package com.arbaba.chordifymobile.engine

/** The server's error codes (chordify_backend/app/audio_io.py), so the app can show the web app's
 *  message for each, plus MODEL_UNAVAILABLE for a model file that fails its SHA-256 check or needs
 *  features this engine does not compute. */
enum class ErrorCode {
    UNSUPPORTED_MEDIA_TYPE, DECODE_FAILED, AUDIO_TOO_SHORT, AUDIO_TOO_LONG, NO_MUSIC_DETECTED, MODEL_UNAVAILABLE,
}

/** An analysis that cannot go on, with the server's code and a message in the server's words. */
class EngineException(val code: ErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the caller's `isCancelled()` returned true. */
class CancelledException : Exception("cancelled")
