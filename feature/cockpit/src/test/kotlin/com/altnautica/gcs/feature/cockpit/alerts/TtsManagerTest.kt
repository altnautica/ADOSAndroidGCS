package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.AlertLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsManagerTest {
    private class FakeEngine : SpeechEngine {
        val spoken = mutableListOf<String>()
        override var isSpeaking = false
        override fun speak(text: String) {
            spoken += text
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private class FakeEngines : SpeechEngineFactory {
        val engine = FakeEngine()
        lateinit var ready: (Boolean) -> Unit
        lateinit var done: () -> Unit
        override fun create(onReady: (Boolean) -> Unit, onDone: () -> Unit): SpeechEngine {
            ready = onReady
            done = onDone
            return engine
        }
    }

    @Test
    fun `a critical line offered while the engine starts is spoken once it is ready`() {
        val engines = FakeEngines()
        val tts = TtsManager(engines)
        tts.initialize()
        tts.speak("Link lost", AlertLevel.Critical)
        assertTrue(engines.engine.spoken.isEmpty())
        engines.ready(true)
        assertEquals(listOf("Link lost"), engines.engine.spoken)
    }

    @Test
    fun `a critical line goes ahead of queued callouts`() {
        val engines = FakeEngines()
        val tts = TtsManager(engines)
        tts.initialize()
        tts.speak("Mode loiter", AlertLevel.Advisory)
        tts.speak("Battery critical", AlertLevel.Critical)
        tts.speak("Mode RTL", AlertLevel.Advisory)
        engines.engine.isSpeaking = true
        engines.ready(true)
        // The engine is busy at start-up: nothing yet; then lines play in order as each finishes.
        engines.engine.isSpeaking = false
        engines.done()
        engines.done()
        assertEquals(listOf("Battery critical", "Mode RTL"), engines.engine.spoken)
    }

    @Test
    fun `a critical line interrupts once the engine is ready`() {
        val engines = FakeEngines()
        val tts = TtsManager(engines)
        tts.initialize()
        engines.ready(true)
        engines.engine.isSpeaking = true
        tts.speak("Mode loiter", AlertLevel.Advisory)
        tts.speak("Geofence breach", AlertLevel.Critical)
        assertEquals(listOf("Geofence breach"), engines.engine.spoken)
    }
}
