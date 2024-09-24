package com.example.assee

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale

class TextRecognitionHandler(
    private val context: Context,
    private val textureView: TextureView
) {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val tts = TextToSpeech(context, null).apply {
        language = Locale.US
    }
    private val longPressDuration: Long = 3000
    private var longPressHandler: android.os.Handler? = null
    private var recognizedText: String? = null

    val touchListener = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longPressHandler = android.os.Handler()
                longPressHandler?.postDelayed(longPressRunnable, longPressDuration)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler?.removeCallbacks(longPressRunnable)
                longPressHandler = null
                true
            }
            else -> false
        }
    }

    private val longPressRunnable = Runnable {
        recognizeText()
    }

    private fun recognizeText() {
        val bitmap = textureView.bitmap
        if (bitmap != null) {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    recognizedText = visionText.text
                    if (recognizedText?.isNotEmpty() == true) {
                        tts.speak(recognizedText, TextToSpeech.QUEUE_FLUSH, null, null)
                    } else {
                        tts.speak("No text recognized", TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
                .addOnFailureListener { e ->
                    tts.speak("Error recognizing text", TextToSpeech.QUEUE_FLUSH, null, null)
                }
        } else {
            Log.e("TextRecognitionHandler", "Bitmap is null")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
