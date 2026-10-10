package com.altnautica.gcs.feature.cockpit.alerts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.altnautica.gcs.core.model.AlertLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var enabled = true
    private var volume = 1.0f

    private val pendingQueue = LinkedList<Pair<String, AlertLevel>>()

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        drainQueue()
                    }
                    override fun onError(utteranceId: String?) {}
                })
                ready = true
                drainQueue()
            } else {
                Log.e("TtsManager", "TTS initialization failed with status $status")
            }
        }
    }

    fun speak(text: String, priority: AlertLevel) {
        if (!enabled) return

        if (priority == AlertLevel.Critical) {
            // Critical alerts interrupt whatever is playing
            tts?.stop()
            pendingQueue.clear()
            speakNow(text)
        } else {
            pendingQueue.add(text to priority)
            drainQueue()
        }
    }

    private fun speakNow(text: String) {
        if (!ready) return
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
    }

    private fun drainQueue() {
        if (!ready) return
        if (tts?.isSpeaking == true) return
        val next = pendingQueue.poll() ?: return
        speakNow(next.first)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (!enabled) {
            tts?.stop()
            pendingQueue.clear()
        }
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingQueue.clear()
    }
}
