package com.altnautica.gcs.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Android's SpeechRecognizer for voice command input.
 *
 * Uses the built-in speech recognition engine (Google, Samsung, etc.)
 * rather than an offline model like VOSK (which would require bundling
 * large model files). Works offline on devices with downloaded language
 * packs, otherwise requires network.
 *
 * Usage:
 *   recognizer.onResult = { text -> handleCommand(text) }
 *   recognizer.startListening()
 *   // ... user speaks ...
 *   recognizer.stopListening()
 */
@Singleton
class VoiceCommandRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val executor: VoiceCommandExecutor,
) {

    companion object {
        private const val TAG = "VoiceCommandRecognizer"
    }

    private var speechRecognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialResult = MutableStateFlow("")
    val partialResult: StateFlow<String> = _partialResult.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Called with the final recognized text when speech recognition completes. */
    var onResult: ((String) -> Unit)? = null

    val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening() {
        if (_isListening.value) return
        if (!isAvailable) {
            _error.value = "Speech recognition not available on this device"
            return
        }

        _error.value = null
        _partialResult.value = ""

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isListening.value = true
                Log.d(TAG, "Ready for speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Could be used for a voice level meter
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isListening.value = false
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                _isListening.value = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                    SpeechRecognizer.ERROR_CLIENT -> "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Missing permission"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    else -> "Unknown error ($error)"
                }
                _error.value = msg
                Log.w(TAG, "Recognition error: $msg")
            }

            override fun onResults(results: Bundle?) {
                _isListening.value = false
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull() ?: ""
                Log.i(TAG, "Result: $text")
                if (text.isNotEmpty()) {
                    onResult?.invoke(text)
                    // Match against grammar and propose for confirmation
                    val action = CommandGrammar.match(text)
                    if (action != null) {
                        val voiceCmd = CommandGrammar.toVoiceCommand(action)
                        executor.propose(voiceCmd)
                        Log.i(TAG, "Proposed voice command: ${voiceCmd.label}")
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                _partialResult.value = partial
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Support Hindi + English
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                "en-IN",
            )
        }

        recognizer.startListening(intent)
        Log.i(TAG, "Started listening")
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _isListening.value = false
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        _isListening.value = false
    }
}
