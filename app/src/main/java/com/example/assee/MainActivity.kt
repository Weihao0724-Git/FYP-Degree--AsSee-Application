package com.example.assee

import CameraHandler
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.TextureView
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.assee.ml.EfficientDetLiteD0
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraHandler: CameraHandler
    private lateinit var objectDetection: ObjectDetection
    private lateinit var textRecognition: TextRecognitionHandler
    private lateinit var voiceCommandHandler: VoiceCommandHandler
    private lateinit var tts: TextToSpeech

    private lateinit var imageView: ImageView
    private lateinit var textureView: TextureView
    private lateinit var button1: Button
    private lateinit var button2: Button
    private lateinit var button3: Button

    private var currentMode = RecognitionMode.OBJECT_DETECTION

    enum class RecognitionMode {
        OBJECT_DETECTION, TEXT_RECOGNITION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)
        button1 = findViewById(R.id.button1)
        button2 = findViewById(R.id.button2)
        button3 = findViewById(R.id.button3)

        tts = TextToSpeech(this, this)

        getPermissions()

        val labels = FileUtil.loadLabels(this, "labels.txt")
        val imageProcessor = ImageProcessor.Builder()
            .add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR))
            .build()
        val model = EfficientDetLiteD0.newInstance(this)

        cameraHandler = CameraHandler(this, textureView)
        objectDetection = ObjectDetection(this, model, labels, imageProcessor,imageView)
        textRecognition = TextRecognitionHandler(this, textureView)
        voiceCommandHandler = VoiceCommandHandler(this,this)

        textureView.surfaceTextureListener = cameraHandler.surfaceTextureListener

        button1.setOnClickListener {
            wakeUpAssistant()
        }

        button2.setOnClickListener {
            switchToTextRecognitionMode()
        }

        button3.setOnClickListener {
            switchToObjectDetectionMode()
        }
    }

    fun switchToObjectDetectionMode() {
        currentMode = RecognitionMode.OBJECT_DETECTION
        tts.speak("Switched to object detection mode", TextToSpeech.QUEUE_FLUSH, null, null)

        cameraHandler.startCapturingFrames { bitmap: Bitmap ->
            objectDetection.processFrame(bitmap)
        }
    }

    fun switchToTextRecognitionMode() {
        currentMode = RecognitionMode.TEXT_RECOGNITION
        tts.speak("Switched to text recognition mode", TextToSpeech.QUEUE_FLUSH, null, null)

        cameraHandler.stopCapturingFrames()

        imageView.setImageDrawable(null)
        textureView.setOnTouchListener(textRecognition.touchListener)
    }

    private fun wakeUpAssistant() {
        voiceCommandHandler.startListeningForCommands()
    }

    override fun onDestroy() {
        super.onDestroy()
        objectDetection.closeModel()
        textRecognition.shutdown()
        voiceCommandHandler.shutdown()
        tts.shutdown()
    }

    private fun getPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onInit(status: Int) {
    }

}
