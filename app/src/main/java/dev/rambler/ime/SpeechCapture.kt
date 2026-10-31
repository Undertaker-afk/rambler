package dev.rambler.ime

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Continuous dictation on top of Android's SpeechRecognizer. The platform recognizer stops after
 * a short pause, so we chain sessions and collect everything until the user stops (or goes quiet
 * for a while after having said something).
 */
class SpeechCapture(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinished: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private val committed = StringBuilder()
    private var running = false
    private var stopRequested = false

    val isRunning get() = running

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("No speech recognition service on this device"); return
        }
        committed.clear()
        running = true
        stopRequested = false
        recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
        listen()
    }

    /** User tapped stop: finish the current utterance, then deliver everything. */
    fun stop() {
        if (!running) return
        stopRequested = true
        recognizer?.stopListening()
        // If the recognizer is idle between sessions, no callback will arrive, so finish now.
        if (!sessionActive) finish()
    }

    fun destroy() {
        running = false
        recognizer?.destroy()
        recognizer = null
    }

    private var sessionActive = false

    private fun createRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        }
        sessionActive = true
        recognizer?.startListening(intent)
    }

    private fun finish() {
        if (!running) return
        running = false
        recognizer?.destroy()
        recognizer = null
        onFinished(committed.toString().trim())
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            sessionActive = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) committed.append(text.trim()).append(' ')
            onPartial(committed.toString().trim())
            if (stopRequested) finish() else if (running) listen()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            onPartial((committed.toString() + p).trim())
        }

        override fun onError(error: Int) {
            sessionActive = false
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            when {
                !running -> Unit
                stopRequested -> finish()
                // Went quiet after saying something: that's "finished".
                quiet && committed.isNotBlank() -> finish()
                // Nothing said yet: keep waiting.
                quiet -> listen()
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listen()
                else -> {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission needed - open the Rambler app"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Offline speech model missing - install one in system settings"
                        else -> "Speech error $error"
                    }
                    running = false
                    recognizer?.destroy(); recognizer = null
                    if (committed.isNotBlank()) onFinished(committed.toString().trim()) else onError(msg)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
