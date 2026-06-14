package com.monday.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * ═══════════════════════════════════════════════════════════════════════
 * VOICE RECOGNITION MANAGER — Bengali + English + Banglish
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Handles voice input using Android's built-in SpeechRecognizer.
 * Free, works offline for basic recognition, online for better accuracy.
 *
 * Language strategy:
 * - Primary: bn-BD (Bangladeshi Bengali) — catches Bengali + Banglish
 * - Fallback: en-US (English) — if Bengali recognition fails
 *
 * HOW TO TRIGGER:
 * - startListening() → begins one voice recognition session
 * - The callback fires with the recognized text
 * - Call again after each recognition for continuous mode
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStarted: () -> Unit
) {
    companion object {
        private const val TAG = "VoiceRecognition"

        // Primary language: Bangladeshi Bengali (catches Banglish too)
        private const val LANG_BENGALI = "bn-BD"
        private const val LANG_ENGLISH = "en-US"
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var useEnglishFallback = false

    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device")
            return
        }
        createRecognizer()
    }

    private fun createRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(MondayRecognitionListener())
        }
    }

    fun startListening() {
        if (isListening) return

        val language = if (useEnglishFallback) LANG_ENGLISH else LANG_BENGALI

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)

            // Also recognize English words mixed in
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "$language,en-US")

            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            onListeningStarted()
            Log.d(TAG, "Listening started (lang: $language)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening: ${e.message}")
            onError("Microphone start করতে পারিনি")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        isListening = false
    }

    // ─── Recognition Listener ─────────────────────────────────────────────────

    private inner class MondayRecognitionListener : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech detected")
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Can use for waveform animation (rms = volume level)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListening = false
            Log.d(TAG, "Speech ended")
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val bestResult = matches?.firstOrNull()

            if (!bestResult.isNullOrBlank()) {
                Log.d(TAG, "Recognized: '$bestResult'")
                useEnglishFallback = false // Reset fallback on success
                onResult(bestResult)
            } else {
                Log.w(TAG, "Empty recognition result")
                if (!useEnglishFallback) {
                    // Try English if Bengali had no result
                    useEnglishFallback = true
                    startListening()
                } else {
                    useEnglishFallback = false
                    onError("বুঝতে পারিনি, আবার বলুন")
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // Partial results can be used for real-time display
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) {
                Log.d(TAG, "Partial: '$partial'")
            }
        }

        override fun onError(error: Int) {
            isListening = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                SpeechRecognizer.ERROR_NETWORK -> "Network error — check internet"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error ($error)"
            }

            Log.w(TAG, "Recognition error: $errorMsg (code: $error)")

            // Recreate recognizer on certain errors
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT) {
                createRecognizer()
            }

            // Don't report minor errors to user
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                onError(errorMsg)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
