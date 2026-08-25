package com.voiceaccess.messenger.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Headless speech capture (no system "Speak now" dialog) used mid-flow for
 * hands-free reply capture, e.g. "say reply, skip, or done" after a message
 * is read aloud. [SpeechRecognizer] must be driven from the main thread,
 * which is why every call is posted through [mainHandler].
 *
 * Every stage is logged under [TAG] because "the app didn't seem to listen"
 * is otherwise completely opaque — it can mean the mic never opened
 * (permission/recognizer-unavailable), it opened but heard nothing
 * (ERROR_SPEECH_TIMEOUT), it heard something unintelligible
 * (ERROR_NO_MATCH), or it worked and the caller's keyword matching just
 * didn't recognize the transcript. Filter logcat with
 * `adb logcat -s VAM-SpeechToText` to tell those apart.
 */
class SpeechToTextManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Suspends until one utterance is recognized, or returns null on
     * timeout/error/no speech. [biasingStrings] (e.g. contact names) hints
     * the recognizer toward words it would otherwise mis-transcribe —
     * see RecognizerIntent.EXTRA_BIASING_STRINGS. Ignored gracefully on
     * recognizer implementations that don't support it.
     *
     * [onSpeechDetected], if given, fires the instant the recognizer
     * registers the *start* of speech (`onBeginningOfSpeech`) — well before
     * the transcript is ready. This is what lets a caller "barge in": start
     * this listen concurrently with [TextToSpeechManager.speak], and stop
     * the TTS the moment the user starts talking over it, rather than
     * waiting for the whole message to finish first. Real caveat, not
     * glossed over: without a dedicated echo-cancelling audio path, the mic
     * can pick up the phone's own speaker output while it's talking, which
     * may itself trigger this callback on some devices/rooms — most modern
     * devices apply acoustic echo cancellation to VOICE_RECOGNITION-source
     * audio automatically, but it isn't guaranteed on every device.
     */
    suspend fun listenOnce(
        timeoutMs: Long = 8_000,
        biasingStrings: List<String> = emptyList(),
        onSpeechDetected: (() -> Unit)? = null,
    ): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "listenOnce: SpeechRecognizer.isRecognitionAvailable() is false — this device has no " +
                "speech recognition service available (Google app disabled/missing, or no default assistant " +
                "provides one). Nothing will ever be captured until that's fixed on-device.")
            return null
        }

        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                mainHandler.post {
                    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        // This headless path has no "Speak now"/waveform UI to reassure the
                        // user it's actually listening, so the default (short) endpointer
                        // timings read as "you have to be loud and precise" — normal speech
                        // at a normal pace gets cut off as silence before it's finished, or
                        // never registers as "speech" at all against a noisy room. Loosening
                        // these — and explicitly *not* preferring the offline model, which is
                        // noticeably less sensitive — is the actual public lever available
                        // here; there's no API to raise mic gain/AGC, that's fixed by the OS.
                        // Scaled off timeoutMs (capped) rather than a fixed constant, so these
                        // never exceed — and cause the recognizer to get cut off by — this
                        // call's own outer timeout, which differs between call sites (a short
                        // 6s reply/skip/done listen vs. a longer 12s reply-dictation capture).
                        val silenceMs = (timeoutMs / 3).coerceAtMost(2_500L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_500L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, silenceMs)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                        if (biasingStrings.isNotEmpty()) {
                            putStringArrayListExtra(ContactsProvider.EXTRA_BIASING_STRINGS, ArrayList(biasingStrings))
                        }
                    }

                    fun finish(text: String?) {
                        if (continuation.isActive) continuation.resume(text)
                        recognizer.destroy()
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            Log.d(TAG, "listenOnce: onReadyForSpeech — mic is open and listening")
                        }
                        override fun onBeginningOfSpeech() {
                            Log.d(TAG, "listenOnce: onBeginningOfSpeech — heard the start of speech")
                            onSpeechDetected?.invoke()
                        }
                        override fun onEndOfSpeech() {
                            Log.d(TAG, "listenOnce: onEndOfSpeech")
                        }
                        override fun onResults(results: Bundle) {
                            val transcripts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            Log.d(TAG, "listenOnce: onResults=$transcripts")
                            finish(transcripts?.firstOrNull())
                        }
                        override fun onError(error: Int) {
                            Log.w(TAG, "listenOnce: onError=${describeError(error)}")
                            finish(null)
                        }
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    continuation.invokeOnCancellation { mainHandler.post { recognizer.destroy() } }
                    Log.d(TAG, "listenOnce: starting recognizer (timeoutMs=$timeoutMs biasingStrings=${biasingStrings.size})")
                    recognizer.startListening(intent)
                }
            }
        }

        if (result == null) {
            Log.w(TAG, "listenOnce: no transcript within ${timeoutMs}ms")
        }
        return result
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO (mic hardware problem)"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS (RECORD_AUDIO not granted)"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH (heard audio, couldn't transcribe it)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT (no speech detected before the recognizer's own timeout)"
        else -> "UNKNOWN($error)"
    }

    private companion object {
        private const val TAG = "VAM-SpeechToText"
    }
}
