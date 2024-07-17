package com.example.assee

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.assee.ml.SsdMobilenetV11Metadata1
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    lateinit var labels: List<String>
    var colors = listOf(
        Color.BLUE,
        Color.GREEN,
        Color.RED,
        Color.CYAN,
        Color.GRAY,
        Color.BLACK,
        Color.DKGRAY,
        Color.MAGENTA,
        Color.YELLOW,
        Color.RED
    )
    val paint = Paint()
    lateinit var imageProcessor: ImageProcessor
    lateinit var bitmap: Bitmap
    lateinit var imageView: ImageView
    lateinit var cameraDevice: CameraDevice
    lateinit var handler: Handler
    lateinit var cameraManager: CameraManager
    lateinit var textureView: TextureView
    lateinit var model: SsdMobilenetV11Metadata1
    private lateinit var tts: TextToSpeech
    private lateinit var recognizer: com.google.mlkit.vision.text.TextRecognizer
    private lateinit var speechRecognizer: android.speech.SpeechRecognizer
    private var currentMode = RecognitionMode.OBJECT_DETECTION
    private var isTextRecognized = false
    private var lastDetectedObjects: List<String> = emptyList()
    private var isListeningForCommands = false
    private var commandTimeoutHandler: Handler? = null

    enum class RecognitionMode {
        OBJECT_DETECTION, TEXT_RECOGNITION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)

        getPermissions()

        labels = FileUtil.loadLabels(this, "test.txt")
        imageProcessor =
            ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        tts = TextToSpeech(this, this)

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture, width: Int, height: Int
            ) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture, width: Int, height: Int
            ) {
            }

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                if (currentMode == RecognitionMode.OBJECT_DETECTION) {
                    bitmap = textureView.bitmap!!
                    processFrameForObjectDetection(bitmap)
                } else if (currentMode == RecognitionMode.TEXT_RECOGNITION && !isTextRecognized) {
                    bitmap = textureView.bitmap!!
                    processFrameForTextRecognition(bitmap)
                }
            }
        }

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        initSpeechRecognizer()
        startListeningForWakeWord()
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        stopListeningForWakeWord()
        if (this::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }
        commandTimeoutHandler?.removeCallbacksAndMessages(null)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val sizeList = outputSizes?.toList() ?: emptyList()
            val largest = Collections.max(sizeList, CompareSizesByArea())

            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(largest.width, largest.height)
            val surface = Surface(surfaceTexture)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCameraPreviewSession(surface)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }
            }, handler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreviewSession(surface: Surface) {
        try {
            val builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)

            cameraDevice.createCaptureSession(
                listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return

                        try {
                            val captureRequest = builder.build()
                            session.setRepeatingRequest(captureRequest, null, handler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity, "Failed to configure camera", Toast.LENGTH_SHORT
                        ).show()
                    }
                }, handler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun processFrameForObjectDetection(bitmap: Bitmap) {
        try {
            var tfImage = TensorImage.fromBitmap(bitmap)
            tfImage = imageProcessor.process(tfImage)

            val outputs = model.process(tfImage)

            val locations = outputs.locationsAsTensorBuffer
            val classes = outputs.classesAsTensorBuffer
            val scores = outputs.scoresAsTensorBuffer
            val numDetections = outputs.numberOfDetectionsAsTensorBuffer

            val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutable)

            val detectedObjects = mutableSetOf<String>()
            val detectedObjectsLabel = mutableSetOf<String>()
            val detectedObjectsInfo = mutableMapOf<String, Pair<String, String>>()

            for (i in 0 until numDetections.floatArray[0].toInt()) {
                val score = scores.floatArray[i]
                if (score > 0.5) {
                    val classIndex = classes.floatArray[i].toInt()
                    val label = labels[classIndex] + String.format("%.2f%%", score * 100)
                    val labelOnly = labels[classIndex]
                    val location = RectF(
                        locations.floatArray[i * 4 + 1] * mutable.width,
                        locations.floatArray[i * 4] * mutable.height,
                        locations.floatArray[i * 4 + 3] * mutable.width,
                        locations.floatArray[i * 4 + 2] * mutable.height
                    )

                    detectedObjects.add(label)
                    detectedObjectsLabel.add(labelOnly)

                    paint.color = colors[classIndex % colors.size]
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2.0f
                    canvas.drawRect(location, paint)

                    paint.style = Paint.Style.FILL
                    val boxedLocation = location.apply { inset(-10f, -10f) }
                    paint.alpha = 50
                    canvas.drawRect(boxedLocation, paint)
                    paint.alpha = 255

                    val distanceDescription =
                        getDistanceDescription(location, mutable.width, mutable.height)
                    val positionDescription =
                        getPositionDescription(location, mutable.width, mutable.height)

                    detectedObjectsInfo[labelOnly] = Pair(distanceDescription, positionDescription)
                }
            }

            runOnUiThread {
                imageView.setImageBitmap(mutable)

                val newObjects = detectedObjectsLabel.subtract(lastDetectedObjects)
                newObjects.forEach { newObject ->
                    val (distanceDescription, positionDescription) = detectedObjectsInfo[newObject]
                        ?: Pair("", "")
                    val message =
                        "New object detected: $newObject. $distanceDescription $positionDescription"
                    tts.speak(message, TextToSpeech.QUEUE_ADD, null, null)
                }

                lastDetectedObjects = detectedObjectsLabel.toList()
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var lastRecognizedText: String? = null
    private var isTextRecognitionOngoing = false

    private fun processFrameForTextRecognition(bitmap: Bitmap) {
        if (isTextRecognitionOngoing) {
            return
        }

        isTextRecognitionOngoing = true
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val resultText = visionText.text
                    if (resultText.isNotEmpty()) {
                        if (resultText == lastRecognizedText) {
                            tts.stop()
                        }
                        tts.speak(resultText, TextToSpeech.QUEUE_ADD, null, null)
                        lastRecognizedText = resultText
                    }

                    isTextRecognized = false
                    isTextRecognitionOngoing = false
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                    isTextRecognitionOngoing = false
                }
        } catch (e: Exception) {
            e.printStackTrace()
            isTextRecognitionOngoing = false
        }
    }

    private fun getPermissions() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1
            )
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                isListeningForCommands = false
                restartListeningAfterDelay()
            }

            override fun onResults(results: Bundle?) {
                results?.let {
                    val matches =
                        it.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.get(0)?.let { command ->
                        handleVoiceCommand(command)
                    }
                }
                restartListeningAfterDelay()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.let {
                    val matches =
                        it.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.get(0)?.let { command ->
                        handleVoiceCommand(command)
                    }
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListeningForWakeWord() {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        intent.putExtra(
            android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 5000
        )
        intent.putExtra(
            android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
            5000
        )
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        speechRecognizer.startListening(intent)
    }

    private fun stopListeningForWakeWord() {
        speechRecognizer.cancel()
    }

    private fun restartListeningAfterDelay() {
        commandTimeoutHandler?.removeCallbacksAndMessages(null)
        commandTimeoutHandler = Handler()
        commandTimeoutHandler?.postDelayed({
            startListeningForWakeWord()
        }, 5000)
    }

    private fun handleVoiceCommand(command: String) {
        when {
            command.contains("ok", true) -> {
                currentMode = RecognitionMode.TEXT_RECOGNITION
                isTextRecognized = false
                tts.speak("Switched to text recognition mode", TextToSpeech.QUEUE_FLUSH, null, null)
            }
            command.contains("test", true) -> {
                currentMode = RecognitionMode.OBJECT_DETECTION
                tts.speak("Switched to object detection mode", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                openCamera()
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "Language not supported", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getDistanceDescription(location: RectF, imageWidth: Int, imageHeight: Int): String {
        val centerX = location.centerX()
        val centerY = location.centerY()

        return if (centerX < imageWidth / 3) {
            "left"
        } else if (centerX > 2 * imageWidth / 3) {
            "right"
        } else {
            "center"
        } + " and " + if (centerY < imageHeight / 3) {
            "top"
        } else if (centerY > 2 * imageHeight / 3) {
            "bottom"
        } else {
            "middle"
        }
    }

    private fun getPositionDescription(location: RectF, imageWidth: Int, imageHeight: Int): String {
        val focalLengthMm = 4.0
        val sensorWidthPixels = imageWidth.toDouble()
        val pixelSizeUm = 1.0

        val objectWidthPixels = location.width() * imageWidth

        val distanceMeters = (objectWidthPixels * focalLengthMm) / (sensorWidthPixels * pixelSizeUm * 1000.0)
        return String.format("%.2f meters", distanceMeters)
    }


    inner class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }
}


