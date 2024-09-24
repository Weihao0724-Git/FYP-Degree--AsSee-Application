import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.core.app.ActivityCompat

class CameraHandler(
    private val context: Context,
    private val textureView: TextureView,
) {

    private lateinit var cameraDevice: CameraDevice
    private var cameraManager: CameraManager
    private var handler: Handler
    private val handlerThread = HandlerThread("videoThread")

    private var isCapturingFrames: Boolean = false
    private var frameCaptureCallback: ((Bitmap) -> Unit)? = null

    init {
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
            if (textureView.isAvailable && isCapturingFrames) {
                textureView.bitmap?.let { bitmap ->  // Use a safe call with let
                    frameCaptureCallback?.invoke(bitmap)
                }
            }
        }
    }

    private fun openCamera() {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            val cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = map?.getOutputSizes(SurfaceTexture::class.java)
            val sizeList = outputSizes?.toList() ?: emptyList()
            val largest = sizeList.maxByOrNull { it.width * it.height } ?: return

            val surfaceTexture = textureView.surfaceTexture
            surfaceTexture?.setDefaultBufferSize(largest.width, largest.height)
            val surface = Surface(surfaceTexture)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d("CameraHandler", "Camera opened")
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
                        try {
                            val captureRequest = builder.build()
                            session.setRepeatingRequest(captureRequest, null, handler)
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                }, handler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }


    fun startCapturingFrames(callback: (Bitmap) -> Unit) {
        isCapturingFrames = true
        frameCaptureCallback = callback
    }

    fun stopCapturingFrames() {
        isCapturingFrames = false
        frameCaptureCallback = null
    }
}
