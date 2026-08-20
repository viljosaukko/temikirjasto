package com.kirjasto.kirjastobotti

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures JPEG frames with Camera2 and exposes them as an MJPEG stream.
 *
 * temi's public SDK does not expose a general-purpose camera-frame stream;
 * the temi documentation describes live video as part of video calls. This
 * class therefore attempts the normal Android camera interface and reports
 * "unavailable" if the temi firmware reserves/hides its cameras.
 */
class CameraStreamer(private val context: Context) {
    companion object {
        private const val TAG = "KirjastobottiCamera"
        private const val WIDTH = 640
        private const val HEIGHT = 480
        private const val JPEG_QUALITY = 72
    }

    private val latest = AtomicReference<ByteArray?>(null)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile var isRunning = false
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        try {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val c = manager.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()

            if (cameraId == null) {
                Log.w(TAG, "No Android camera exposed by temi")
                return
            }

            thread = HandlerThread("temi-camera").also { it.start() }
            handler = Handler(thread!!.looper)

            reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { image ->
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        latest.set(bytes)
                    }
                }, handler)
            }

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val surface = reader!!.surface
                    device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            try {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                                }.build()
                                s.setRepeatingRequest(request, null, handler)
                                isRunning = true
                                Log.i(TAG, "Camera started: $cameraId")
                            } catch (e: Exception) {
                                Log.e(TAG, "Camera request failed", e)
                                stop()
                            }
                        }

                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(TAG, "Camera capture session configuration failed")
                            stop()
                        }
                    }, handler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    stop()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    device.close()
                    stop()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start camera", e)
            stop()
        }
    }

    fun stop() {
        isRunning = false
        try { session?.close() } catch (_: Exception) {}
        try { camera?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        session = null
        camera = null
        reader = null
        latest.set(null)
        thread?.quitSafely()
        thread = null
        handler = null
    }

    fun writeMjpegStream(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=frame\r\nCache-Control: no-store, no-cache, must-revalidate\r\nPragma: no-cache\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.flush()

        var last = latest.get()
        var misses = 0
        try {
            while (misses < 100) {
                val frame = latest.get()
                if (frame != null && frame !== last) {
                    out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(frame)
                    out.write("\r\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    last = frame
                    misses = 0
                } else {
                    misses++
                    Thread.sleep(50)
                }
            }
        } catch (_: Exception) {
            // Browser disconnected.
        }
    }
}
