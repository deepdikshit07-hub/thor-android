package com.thor.assistant

/** Thin JNI bridge to whisper.cpp. */
internal class ThorWhisperNative {
    companion object {
        init { System.loadLibrary("thor_whisper") }
    }
    external fun nativeCreate(modelPath: String): Long
    external fun nativeTranscribe(context: Long, samples: FloatArray, language: String): String
    external fun nativeDestroy(context: Long)
}
