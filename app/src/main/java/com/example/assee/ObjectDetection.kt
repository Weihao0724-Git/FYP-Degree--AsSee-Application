package com.example.assee

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.ImageView
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import com.example.assee.ml.EfficientDetLiteD0
import java.util.Locale

class ObjectDetection(
    private val context: Context,
    private val model: EfficientDetLiteD0,
    private val labels: List<String>,
    private val imageProcessor: ImageProcessor,
    private val imageView: ImageView
) {

    private val colors = listOf(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN,
        Color.GRAY, Color.BLACK, Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
    )
    private val paint = Paint()
    private var lastSpokenObjects = mutableListOf<String>()
    private val tts = TextToSpeech(context, null).apply {
        language = Locale.US
    }

    fun processFrame(bitmap: Bitmap) {
        var image = TensorImage.fromBitmap(bitmap)
        image = imageProcessor.process(image)

        val outputs = model.process(image)
        val locations = outputs.locationAsTensorBuffer
        val classes = outputs.categoryAsTensorBuffer
        val scores = outputs.scoreAsTensorBuffer

        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val h = mutable.height
        val w = mutable.width
        paint.textSize = h / 15f
        paint.strokeWidth = h / 85f

        val detectedObjects = mutableListOf<String>()

        for (i in 0 until scores.floatArray.size) {
            val score = scores.getFloatValue(i)
            if (score > 0.5) {
                val objectClass = labels[classes.getIntValue(i)]
                val location = locations.floatArray.sliceArray(i * 4 until (i + 1) * 4)

                val position = determineObjectPosition(location, w, h)

                if (!detectedObjects.contains(objectClass)) {

                    detectedObjects.add("$objectClass at $position")

                    paint.color = colors[classes.getIntValue(i) % colors.size]
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(
                        RectF(location[1] * w, location[0] * h, location[3] * w, location[2] * h), paint
                    )
                    paint.style = Paint.Style.FILL
                    canvas.drawText("$objectClass, $score", location[1] * w, location[0] * h - 10, paint)
                }
            }
        }

        val removedObjects = lastSpokenObjects.filter { !detectedObjects.contains(it) }

        lastSpokenObjects.removeAll(removedObjects)

        val newObjects = detectedObjects.filter { !lastSpokenObjects.contains(it) }

        if (newObjects.isNotEmpty()) {
            lastSpokenObjects.addAll(newObjects)

            val objectsToSpeak = newObjects.sorted().joinToString(", ")

            Log.d("Object Detection", "NEW SPOKEN OBJECTS: $objectsToSpeak")

            tts.speak(objectsToSpeak, TextToSpeech.QUEUE_FLUSH, null, null)
        }

        imageView.post {
            imageView.setImageBitmap(mutable)
        }
    }

    private fun determineObjectPosition(location: FloatArray, width: Int, height: Int): String {
        val centerX = (location[1] + location[3]) / 2 * width
        val centerY = (location[0] + location[2]) / 2 * height

        val horizontalMargin = width / 30  // 10% margin on left and right
        val verticalMargin = height / 30   // 10% margin on top and bottom

        val verticalPosition = when {
            centerY < height / 3 - verticalMargin -> "top"
            centerY > 2 * height / 3 + verticalMargin -> "bottom"
            else -> "middle"
        }

        // Refine horizontal position
        val horizontalPosition = when {
            centerX < width / 3 - horizontalMargin -> "left"
            centerX > 2 * width / 3 + horizontalMargin -> "right"
            else -> "center"
        }

        // Combine both positions
        return "$verticalPosition $horizontalPosition"
    }


    fun closeModel() {
        model.close()
        tts.shutdown()
    }
}
