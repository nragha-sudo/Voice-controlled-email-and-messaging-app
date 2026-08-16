package com.voiceaccess.messenger.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Headless speech capture (no system "Speak now" dialog) used mid-flow for
 * hands-free reply capture, e.g. "say reply, skip, or done" after a message
 * is read aloud. [SpeechRecognizer] must be driven from the main thread,
 * which is why every call is posted through [mainHandler].
 */
class SpeechToTextManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Suspends until one utterance is recognized, or returns null on timeout/error/no speech. */
    suspend fun listenOnce(timeoutMs: Long = 8_000): String? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        if (continuation.isActive) continuation.resume(null)
                        return@post
                    }

                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    }

                    fun finish(text: String?) {
                        if (continuation.isActive) continuation.resume(text)
                        recognizer.destroy()
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onResults(results: Bundle) {
                            finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                        }
                        override fun onError(error: Int) = finish(null)
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    continuation.invokeOnCancellation { mainHandler.post { recognizer.destroy() } }
                    recognizer.startListening(intent)
                }
            }
        }
}
