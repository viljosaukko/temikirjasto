package com.kirjasto.kirjastobotti

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File

/**
 * Small helper that wraps ML Kit text recognition in a blocking call suitable for calling
 * from a background thread. Uses Tasks.await which will block the current thread until
 * the recognition completes. Call this from Dispatchers.IO.
 *
 * This version adds a lightweight preprocessing step (resize, grayscale, contrast, binarize)
 * to improve OCR accuracy for photographed shelf labels.
 */
object ImageOcr {
    private const val TAG = "ImageOcr"

    fun recognizeTextBlocking(context: Context, imageFile: File): String {
        return try {
            // Load bitmap with sizing to avoid OOM on very large images
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
            val maxDim = 1600
            var inSampleSize = 1
            val maxSide = Math.max(options.outWidth, options.outHeight)
            if (maxSide > maxDim) {
                inSampleSize = Integer.highestOneBit(maxSide / maxDim)
                if (inSampleSize < 1) inSampleSize = 1
            }
            val decodeOptions = BitmapFactory.Options()
            decodeOptions.inSampleSize = inSampleSize
            decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888
            val bmp = BitmapFactory.decodeFile(imageFile.absolutePath, decodeOptions) ?: return ""

            val processed = preprocessBitmap(bmp)
            val image = InputImage.fromBitmap(processed, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val result = Tasks.await(recognizer.process(image))
            result.text ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "OCR failed for ${imageFile.absolutePath}", e)
            ""
        }
    }

    private fun preprocessBitmap(src: Bitmap): Bitmap {
        // Scale down if needed to limit size
        val maxDim = 1600
        val w = src.width
        val h = src.height
        val scale = if (w > maxDim || h > maxDim) {
            val ratio = maxDim.toFloat() / Math.max(w, h)
            ratio
        } else 1f

        val scaled = if (scale != 1f) Bitmap.createScaledBitmap(src, (w * scale).toInt(), (h * scale).toInt(), true) else src.copy(Bitmap.Config.ARGB_8888, true)

        // Convert to grayscale and increase contrast
        val bmp = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f) // grayscale
        // simple contrast tweak
        val contrast = 1.25f
        val scaleContrast = floatArrayOf(
            contrast, 0f, 0f, 0f, 0f,
            0f, contrast, 0f, 0f, 0f,
            0f, 0f, contrast, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(ColorMatrix(scaleContrast))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        // Binarize to improve contrast for OCR
        val bw = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val threshold = 150 // 0..255
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (0.3 * r + 0.59 * g + 0.11 * b).toInt()
            pixels[i] = if (lum < threshold) Color.BLACK else Color.WHITE
        }
        bw.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bw
    }
}
