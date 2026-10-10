package com.altnautica.gcs.feature.cockpit.alerts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.altnautica.gcs.core.model.AlertLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A speech engine; [AndroidSpeechEngines] wraps the platform text-to-speech. */
interface SpeechEngine {
    val isSpeaking: Boolean

    /** Speaks [text] at once, cutting off anything playing. */
    fun speak(text: String)

    fun stop()

    fun shutdown()
}

/** Creates an engine; [onReady] reports initialisation, [onDone] each finished utterance. Both may run on any thread. */
fun interface SpeechEngineFactory {
    fun create(onReady: (Boolean) -> Unit, onDone: () -> Unit): SpeechEngine
}

private class AndroidSpeechEngines(private val context: Context) : SpeechEngineFactory {
    override fun create(onReady: (Boolean) -> Unit, onDone: () -> Unit): SpeechEngine {
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(context) { status ->
            val ok = status == TextToSpeech.SUCCESS
            if (ok) {
                tts.language = Locale.US
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = onDone()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onDone()
                })
            } else {
                Log.e("TtsManager", "TTS initialization failed with status $status")
            }
            onReady(ok)
        }
        return object : SpeechEngine {
            override val isSpeaking: Boolean get() = tts.isSpeaking
            override fun speak(text: String) {
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
            }
            override fun stop() {
                tts.stop()
            }
            override fun shutdown() {
                tts.stop()
                tts.shutdown()
            }
        }
    }
}

/**
 * Cockpit speech. A critical line interrupts anything playing; other lines wait
 * their turn. Lines offered while the engine is still starting are queued, a
 * critical one ahead of every non-critical one, and spoken once it is ready.
 */
@Singleton
class TtsManager internal constructor(private val engines: SpeechEngineFactory) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(AndroidSpeechEngines(context))

    private var engine: SpeechEngine? = null
    private var ready = false
    private val pending = ArrayDeque<Pair<String, AlertLevel>>()

    @Synchronized
    fun initialize() {
        if (engine != null) return
        engine = engines.create(onReady = ::onReady, onDone = ::drainQueue)
    }

    @Synchronized
    private fun onReady(ok: Boolean) {
        ready = ok
        drainQueue()
    }

    @Synchronized
    fun speak(text: String, priority: AlertLevel) {
        if (priority == AlertLevel.Critical) {
            // Critical speech goes ahead of every queued callout.
            pending.removeAll { it.second != AlertLevel.Critical }
            if (ready) {
                engine?.speak(text)
            } else {
                pending.addLast(text to priority)
            }
        } else {
            pending.addLast(text to priority)
            drainQueue()
        }
    }

    @Synchronized
    private fun drainQueue() {
        val e = engine ?: return
        if (!ready || e.isSpeaking) return
        val next = pending.removeFirstOrNull() ?: return
        e.speak(next.first)
    }

    @Synchronized
    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
        pending.clear()
    }
}
