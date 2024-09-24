package com.example.assee

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import org.json.JSONObject

class VoiceCommandHandler(
    private val context: Context,
    private val mainActivity: MainActivity
) {
    val tts: TextToSpeech = TextToSpeech(context) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.e("VoiceCommandHandler", "TextToSpeech initialization failed")
        }
    }

    private lateinit var voskModel: Model
    private var speechService: SpeechService? = null

    init {
        initVoskModel()
    }

    private fun initVoskModel() {
        val modelDir = File(context.filesDir, "vosk-model-small-en-us-0.15")

        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }

        try {
            voskModel = Model(modelDir.absolutePath)
        } catch (e: IOException) {
            Log.e("Vosk", "Error initializing Vosk model: ${e.message}", e)
        }
    }

    fun startListeningForCommands() {
        stopListening()

        try {
            val recognizer = Recognizer(voskModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                }

                override fun onResult(hypothesis: String) {
                    if (hypothesis.isNotEmpty()) {
                        Log.d("Vosk", "Recognized: $hypothesis")
                        handleCommand(hypothesis)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                }

                override fun onError(exception: Exception) {
                    Toast.makeText(context, "Error: ${exception.message}", Toast.LENGTH_LONG).show()
                }

                override fun onTimeout() {
                }
            })
        } catch (e: Exception) {
            Log.e("Vosk", "Error starting speech service: ${e.message}", e)
        }
    }

    private fun handleCommand(hypothesis: String) {
        Log.d("Vosk", "Processing command: $hypothesis")
        try {
            val jsonObject = JSONObject(hypothesis)
            val text = jsonObject.optString("text", "").trim()

            when {
                text.contains("text", ignoreCase = true) -> {
                    mainActivity.switchToTextRecognitionMode()
                }
                text.contains("object", ignoreCase = true) -> {
                    mainActivity.switchToObjectDetectionMode()
                }
                text.contains("volume up", ignoreCase = true) -> {
                    adjustVolume(true)
                    speak("Volume increased")
                }
                text.contains("volume down", ignoreCase = true) -> {
                    adjustVolume(false)
                    speak("Volume decreased")
                }
                text.contains("mute", ignoreCase = true) -> {
                    mute()
                    speak("Muted")
                }
                else -> {
                    speak("Command not recognized")
                    startListeningForCommands()
                }
            }
        } catch (e: Exception) {
            Log.e("Vosk", "Error processing command: ${e.message}", e)
            speak("Command processing error")
        }
        stopListening()
    }

    private fun adjustVolume(increase: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (increase) {
            audioManager.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_PLAY_SOUND)
        } else {
            audioManager.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_PLAY_SOUND)
        }
    }

    private fun mute() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_PLAY_SOUND)
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
        stopListening()
    }
}
