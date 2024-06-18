package com.example.assee

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
import android.widget.ToggleButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import kotlin.collections.ArrayList

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    lateinit var labels: List<String>
    var colors = listOf(
        Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.GRAY, Color.BLACK,
        Color.DKGRAY, Color.MAGENTA, Color.YELLOW, Color.RED
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
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var currentMode = RecognitionMode.OBJECT_DETECTION
    private var isTextRecognized = false
    private var lastSpokenTime = 0L

    enum class RecognitionMode {
        OBJECT_DETECTION,
        TEXT_RECOGNITION
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textureView = findViewById(R.id.textureView)
        imageView = findViewById(R.id.imageView)
        val toggleRecognitionMode: ToggleButton = findViewById(R.id.toggleRecognitionMode)

        getPermissions()

        labels = FileUtil.loadLabels(this, "test.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)

        val handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        tts = TextToSpeech(this, this)

        toggleRecognitionMode.setOnCheckedChangeListener { _, isChecked ->
            currentMode = if (isChecked) {
                RecognitionMode.OBJECT_DETECTION
            } else {
                RecognitionMode.TEXT_RECOGNITION
            }
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

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
    }

    override fun onDestroy() {
        super.onDestroy()
        model.close()
        if (this::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
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

            cameraDevice.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
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
                    Toast.makeText(this@MainActivity, "Failed to configure camera", Toast.LENGTH_SHORT).show()
                }
            }, handler)
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

            val detectedObjects = mutableListOf<String>()

            for (i in 0 until numDetections.floatArray[0].toInt()) {
                val score = scores.floatArray[i]
                if (score > 0.5) {
                    val classIndex = classes.floatArray[i].toInt()
                    val label = labels[classIndex] + String.format("%.2f%%", score * 100)
                    val labelonly = labels[classIndex]
                    val location = RectF(
                        locations.floatArray[i * 4 + 1] * mutable.width,
                        locations.floatArray[i * 4] * mutable.height,
                        locations.floatArray[i * 4 + 3] * mutable.width,
                        locations.floatArray[i * 4 + 2] * mutable.height
                    )

                    paint.color = colors[classIndex % colors.size]
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 10.0f
                    canvas.drawRect(location, paint)

                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    paint.textSize = 80.0f
                    canvas.drawText(label, location.left, location.top, paint)

                    val distance = calculateDistance(location)
                    val position = getPositionDescription(location, mutable.width, mutable.height)
                    if (distance >= 1) {
                        detectedObjects.add("Objects at ${String.format("%.2f", distance)} meters, $position")
                    }else
                    {
                        detectedObjects.add("Objects $labelonly at ${String.format("%.2f", distance)} meters, $position")
                    }
                }
            }

            if (detectedObjects.isNotEmpty() && System.currentTimeMillis() - lastSpokenTime > 5000) {
                tts.speak(detectedObjects.joinToString(", "), TextToSpeech.QUEUE_FLUSH, null, null)
                lastSpokenTime = System.currentTimeMillis()
            }

            runOnUiThread {
                imageView.setImageBitmap(mutable)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun calculateDistance(location: RectF): Float {
        return ((location.width() + location.height() )/7500)
    }

    private fun getPositionDescription(location: RectF, imageWidth: Int, imageHeight: Int): String {
        val centerX = location.centerX()
        val centerY = location.centerY()

        val horizontalPosition = when {
            centerX < imageWidth / 3 -> "left"
            centerX > 2 * imageWidth / 3 -> "right"
            else -> "center"
        }

        val verticalPosition = when {
            centerY < imageHeight / 3 -> "top"
            centerY > 2 * imageHeight / 3 -> "bottom"
            else -> "middle"
        }

        return "$verticalPosition-$horizontalPosition"
    }


    private fun processFrameForTextRecognition(bitmap: Bitmap) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val resultText = visionText.text
                    tts.speak(resultText, TextToSpeech.QUEUE_FLUSH, null, null)
                    isTextRecognized = true
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.RECORD_AUDIO)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            getPermissions()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        } else {
            Toast.makeText(this, "TTS initialization failed", Toast.LENGTH_SHORT).show()
        }
    }

    private class CompareSizesByArea : Comparator<Size> {
        override fun compare(o1: Size, o2: Size): Int {
            return java.lang.Long.signum(o1.width.toLong() * o1.height - o2.width.toLong() * o2.height)
        }
    }
}
