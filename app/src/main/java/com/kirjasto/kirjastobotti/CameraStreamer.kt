package com.kirjasto.kirjastobotti

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.BitmapFactory
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
import kotlin.math.abs
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

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
    @Volatile private var _barcodeScanningEnabled = false
    val barcodeScanningEnabled: Boolean get() = _barcodeScanningEnabled
    @Volatile private var lastBarcode = ""
    @Volatile private var lastBarcodeTime = 0L
    @Volatile var onBarcodeDetected: ((String) -> Unit)? = null
    @Volatile var onCameraError: ((String) -> Unit)? = null
    /** The most recently detected ISBN barcode value (empty string if none yet). Readable by AdminServer for polling. */
    @Volatile var lastDetectedBarcode: String = ""
        private set
    @Volatile var lastDetectedBarcodeTime: Long = 0L
        private set
    private val barcodeScanner = BarcodeScanning.getClient()
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
                onCameraError?.invoke("Temi ei ilmoita käytettävissä olevaa kameraa")
                return
            }

            val characteristics = manager.getCameraCharacteristics(cameraId)
            val jpegSize = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)
                ?.minByOrNull { abs(it.width - WIDTH) + abs(it.height - HEIGHT) }
                ?: throw IllegalStateException("Kamerasta ei löydy tuettua JPEG-kuvakokoa")

            thread = HandlerThread("temi-camera").also { it.start() }
            handler = Handler(thread!!.looper)

            reader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also {
                it.setOnImageAvailableListener({ r ->
                    r.acquireLatestImage()?.use { image ->
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        latest.set(bytes)
                        scanBarcode(bytes)
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
                                    val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                                    if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    } else if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) == true) {
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                                    }
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
                            onCameraError?.invoke("Temin kameran kuvausta ei voitu käynnistää")
                            stop()
                        }
                    }, handler)
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    onCameraError?.invoke("Temin kamera katkesi")
                    stop()
                }

                override fun onError(device: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera open error: $error")
                    device.close()
                    onCameraError?.invoke("Temin kameran avaus epäonnistui ($error)")
                    stop()
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start camera", e)
            onCameraError?.invoke(e.message ?: "Temin kameraa ei voitu avata")
            stop()
        }
    }

    fun setBarcodeScanningEnabled(enabled: Boolean) {
        _barcodeScanningEnabled = enabled
        if (enabled) {
            lastBarcode = ""
            lastBarcodeTime = 0L
        }
    }

    private fun scanBarcode(jpeg: ByteArray) {
        if (!_barcodeScanningEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastBarcodeTime < 700) return
        lastBarcodeTime = now
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val image = InputImage.fromBitmap(bitmap, 0)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstNotNullOfOrNull { it.rawValue } ?: return@addOnSuccessListener
                // ISBN-10/13 only: ignore unrelated product and QR codes.
                val isbn = value.replace("-", "").replace(" ", "")
                if (!isbn.matches(Regex("(?:97[89])?\\d{9}[\\dXx]"))) return@addOnSuccessListener
                if (isbn != lastBarcode) {
                    lastBarcode = isbn
                    lastDetectedBarcode = isbn
                    lastDetectedBarcodeTime = System.currentTimeMillis()
                    onBarcodeDetected?.invoke(isbn)
                }
            }
            .addOnCompleteListener { bitmap.recycle() }
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
