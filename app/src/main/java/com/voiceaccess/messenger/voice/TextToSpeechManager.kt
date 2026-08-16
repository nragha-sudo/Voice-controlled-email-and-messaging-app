package com.voiceaccess.messenger.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Thin coroutine wrapper around [TextToSpeech] so callers can await a phrase finishing. */
class TextToSpeechManager(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready.complete(status == TextToSpeech.SUCCESS)
        }
    }

    /** Speaks [text] and suspends until playback finishes (or fails, or the engine never initialized). */
    suspend fun speak(text: String, timeoutMs: Long = 20_000) {
        val engine = tts ?: return
        val initialized = withTimeoutOrNull(5_000) { ready.await() } ?: false
        if (!initialized || text.isBlank()) return

        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (!completion.isCompleted) completion.complete(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (!completion.isCompleted) completion.complete(Unit)
            }
        })
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        withTimeoutOrNull(timeoutMs) { completion.await() }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
