package com.kirjasto.kirjastobotti

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Data model for a detected bounding box in image pixel coordinates.
 */
data class ShelfBoundingBox(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("x", x)
        json.put("y", y)
        json.put("width", width)
        json.put("height", height)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject?): ShelfBoundingBox? {
            if (json == null) return null
            return ShelfBoundingBox(
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0)
            )
        }
    }
}

/**
 * Structured detection result for a shelf label sign.
 */
data class ShelfDetection(
    val type: String = "shelf_label",
    val rawText: String,
    val normalizedText: String,
    val rangeStart: String?,
    val rangeEnd: String?,
    val section: String? = null,
    val confidence: Double,
    val boundingBox: ShelfBoundingBox?,
    val suggestion: String? = null,
    val requiresVerification: Boolean = false
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", type)
        json.put("raw_text", rawText)
        json.put("normalized_text", normalizedText)
        json.put("range_start", rangeStart ?: JSONObject.NULL)
        json.put("range_end", rangeEnd ?: JSONObject.NULL)
        if (section != null) json.put("section", section)
        json.put("confidence", confidence)
        if (boundingBox != null) {
            json.put("bounding_box", boundingBox.toJson())
        }
        if (suggestion != null) json.put("suggestion", suggestion)
        json.put("requires_verification", requiresVerification)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ShelfDetection {
            val bbJson = json.optJSONObject("bounding_box")
            return ShelfDetection(
                type = json.optString("type", "shelf_label"),
                rawText = json.optString("raw_text", ""),
                normalizedText = json.optString("normalized_text", ""),
                rangeStart = if (json.isNull("range_start")) null else json.optString("range_start"),
                rangeEnd = if (json.isNull("range_end")) null else json.optString("range_end"),
                section = if (json.isNull("section")) null else json.optString("section"),
                confidence = json.optDouble("confidence", 0.5),
                boundingBox = ShelfBoundingBox.fromJson(bbJson),
                suggestion = if (json.isNull("suggestion")) null else json.optString("suggestion"),
                requiresVerification = json.optBoolean("requires_verification", false)
            )
        }
    }
}

data class ShelfDetectionResult(
    val detections: List<ShelfDetection>
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        val arr = JSONArray()
        detections.forEach { arr.put(it.toJson()) }
        json.put("detections", arr)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): ShelfDetectionResult {
            val arr = json.optJSONArray("detections") ?: JSONArray()
            val list = mutableListOf<ShelfDetection>()
            for (i in 0 until arr.length()) {
                list.add(ShelfDetection.fromJson(arr.getJSONObject(i)))
            }
            return ShelfDetectionResult(list)
        }
    }
}

/**
 * Specialized Library Shelf OCR processing engine.
 * Isolates shelf signs from background text, applies custom image preprocessing,
 * parses Finnish classification/letter ranges, normalizes dashes, and computes confidence/suggestions.
 */
object LibraryShelfOcr {
    private const val TAG = "LibraryShelfOcr"

    // Supported dash characters normalized to standard hyphen '-'
    private val DASH_REGEX = Regex("""[–—‒−\-]""")

    // Common Finnish library range patterns
    // e.g. B-C, V-Ö, A, 84.2-84.5, etc.
    private val RANGE_PATTERN = Regex("""(?U)\b([A-ZÅÄÖ0-9]{1,5}(?:\.[0-9]{1,3})?)\s*[-–—‒−]\s*([A-ZÅÄÖ0-9]{1,5}(?:\.[0-9]{1,3})?)\b""", RegexOption.IGNORE_CASE)
    private val SINGLE_TOKEN_PATTERN = Regex("""(?U)\b([A-ZÅÄÖ]{1,4}|[0-9]{1,3}(?:\.[0-9]{1,3})?)\b""", RegexOption.IGNORE_CASE)

    /**
     * Process an image file and return structured shelf detections.
     */
    fun analyzeShelfImageBlocking(imageFile: File): ShelfDetectionResult {
        if (!imageFile.exists()) {
            return ShelfDetectionResult(emptyList())
        }

        return try {
            val bmp = loadOriginalBitmap(imageFile) ?: return ShelfDetectionResult(emptyList())
            analyzeBitmapBlocking(bmp)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze image file: ${imageFile.absolutePath}", e)
            ShelfDetectionResult(emptyList())
        }
    }

    /**
     * Core recognition method for a Bitmap.
     */
    fun analyzeBitmapBlocking(bmp: Bitmap): ShelfDetectionResult {
        return try {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            // Step 1: Run initial ML Kit pass to find text blocks and sign bounding boxes
            val inputImage = InputImage.fromBitmap(bmp, 0)
            val visionText: Text = Tasks.await(recognizer.process(inputImage))

            // Step 2: Filter and locate candidate shelf label regions (ignoring irrelevant background book titles)
            val candidateBlocks = findShelfSignRegions(visionText, bmp.width, bmp.height)

            val detections = mutableListOf<ShelfDetection>()

            if (candidateBlocks.isNotEmpty()) {
                for (block in candidateBlocks) {
                    val boundingBox = block.boundingBox?.let { rect ->
                        ShelfBoundingBox(
                            x = Math.max(0, rect.left),
                            y = Math.max(0, rect.top),
                            width = Math.min(bmp.width - rect.left, rect.width()),
                            height = Math.min(bmp.height - rect.top, rect.height())
                        )
                    }

                    // Step 3 & 4: Crop ROI and apply preprocessing pipeline
                    val croppedBmp = cropBitmap(bmp, block.boundingBox)
                    val processedBmp = preprocessRoiBitmap(croppedBmp)

                    // Step 5: Focused OCR on preprocessed sign ROI
                    val roiImage = InputImage.fromBitmap(processedBmp, 0)
                    val roiTextResult: Text = Tasks.await(recognizer.process(roiImage))
                    val rawText = if (roiTextResult.text.isNotBlank()) roiTextResult.text else block.text

                    // Step 6 & 7: Parse library ranges & compute confidence + suggestions
                    val detection = interpretTextAsShelfDetection(rawText, boundingBox)
                    if (detection != null) {
                        detections.add(detection)
                    }
                }
            }

            // Fallback: If no localized sign ROI matched high confidence, process whole image variants
            if (detections.isEmpty()) {
                val fallbackDetection = runFallbackWholeImageOcr(bmp, visionText)
                if (fallbackDetection != null) {
                    detections.add(fallbackDetection)
                }
            }

            ShelfDetectionResult(detections)
        } catch (e: Throwable) {
            Log.e(TAG, "OCR recognition error", e)
            ShelfDetectionResult(emptyList())
        }
    }

    /**
     * Normalizes dashes into standard hyphen '-' and preserves Finnish uppercase letters.
     */
    fun normalizeText(input: String): String {
        return input
            .replace(DASH_REGEX, "-")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .uppercase()
    }

    /**
     * Checks if text contains a range (e.g. B-C, V-Ö, 84.2-84.5).
     */
    fun parseRangeToken(text: String): Pair<String?, String?>? {
        val normalized = normalizeText(text)

        // Try explicit range pattern (X-Y)
        val rangeMatch = RANGE_PATTERN.find(normalized)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].uppercase()
            val end = rangeMatch.groupValues[2].uppercase()
            return Pair(start, end)
        }

        // Single letter or category token (e.g. "B" or "84.2")
        val singleMatch = SINGLE_TOKEN_PATTERN.find(normalized)
        if (singleMatch != null) {
            val token = singleMatch.groupValues[1].uppercase()
            return Pair(token, token)
        }

        return null
    }

    /**
     * Evaluates OCR output for potential Finnish character misrecognitions or digit/letter confusion.
     * Generates smart suggestions when detected (e.g. V-O or V-0 -> V-Ö).
     */
    fun generateSuggestion(normalizedText: String): Pair<String?, Double> {
        var text = normalizedText
        var suggestion: String? = null
        var penalty = 0.0

        // Common error: V-O or V-0 instead of V-Ö in Finnish shelf ranges
        if (text.contains(Regex("""\bV\s*-\s*[O0]\b"""))) {
            suggestion = text.replace(Regex("""\bV\s*-\s*[O0]\b"""), "V-Ö")
            penalty += 0.25
        } else if (text.contains(Regex("""\b[A-Z]\s*-\s*0\b"""))) {
            // Letter to digit '0' misread, e.g. A-0 -> A-O or A-Ö
            val fix = if (text.startsWith("V-") || text.endsWith("-0")) text.replace("-0", "-Ö") else text.replace("-0", "-O")
            suggestion = fix
            penalty += 0.2
        } else if (text.contains(Regex("""\b[0-9]\s*-\s*O\b"""))) {
            suggestion = text.replace("-O", "-0")
            penalty += 0.2
        }

        return Pair(suggestion, penalty)
    }

    /**
     * Filters ML Kit text blocks to identify shelf sign candidate regions while ignoring
     * background books and long text paragraphs.
     */
    private fun findShelfSignRegions(visionText: Text, imgWidth: Int, imgHeight: Int): List<Text.TextBlock> {
        val candidates = mutableListOf<Text.TextBlock>()

        for (block in visionText.textBlocks) {
            val text = block.text.trim()
            if (text.isBlank()) continue

            val box = block.boundingBox
            val lineCount = block.lines.size
            val wordCount = text.split(Regex("""\s+""")).size

            // Ignore large blocks of text (such as long book descriptions/covers)
            if (wordCount > 15 || lineCount > 6) continue

            val normalized = normalizeText(text)
            val hasRange = RANGE_PATTERN.containsMatchIn(normalized)
            val hasSingle = SINGLE_TOKEN_PATTERN.containsMatchIn(normalized)

            // Favor blocks containing shelf sign indicators
            if (hasRange || (hasSingle && text.length <= 12)) {
                candidates.add(block)
            }
        }

        return candidates
    }

    /**
     * Crops a bounding box region with a small margin.
     */
    private fun cropBitmap(src: Bitmap, box: Rect?): Bitmap {
        if (box == null) return src
        val margin = 16
        val left = Math.max(0, box.left - margin)
        val top = Math.max(0, box.top - margin)
        val right = Math.min(src.width, box.right + margin)
        val bottom = Math.min(src.height, box.bottom + margin)
        val width = right - left
        val height = bottom - top

        if (width <= 0 || height <= 0) return src
        return Bitmap.createBitmap(src, left, top, width, height)
    }

    /**
     * Pipeline step: Upscale, convert to grayscale, increase contrast, and binarize.
     */
    private fun preprocessRoiBitmap(src: Bitmap): Bitmap {
        val minTargetDim = 400
        var w = src.width
        var h = src.height
        var scale = 1.0f

        if (w < minTargetDim || h < minTargetDim) {
            scale = minTargetDim.toFloat() / Math.min(w, h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        val scaled = if (scale != 1.0f) {
            Bitmap.createScaledBitmap(src, w, h, true)
        } else {
            src.copy(Bitmap.Config.ARGB_8888, true)
        }

        // Grayscale & Contrast enhancement
        val dest = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)

        val contrast = 1.4f
        val scaleContrast = floatArrayOf(
            contrast, 0f, 0f, 0f, 0f,
            0f, contrast, 0f, 0f, 0f,
            0f, 0f, contrast, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
        cm.postConcat(ColorMatrix(scaleContrast))
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(scaled, 0f, 0f, paint)

        // Binarization (Otsu threshold approximation)
        val pixels = IntArray(dest.width * dest.height)
        dest.getPixels(pixels, 0, dest.width, 0, 0, dest.width, dest.height)
        val threshold = 140
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            pixels[i] = if (lum < threshold) Color.BLACK else Color.WHITE
        }
        dest.setPixels(pixels, 0, dest.width, 0, 0, dest.width, dest.height)
        return dest
    }

    /**
     * Interprets raw text string into a structured ShelfDetection object.
     */
    private fun interpretTextAsShelfDetection(rawText: String, boundingBox: ShelfBoundingBox?): ShelfDetection? {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null

        val normalizedFull = normalizeText(rawText)
        val rangePair = parseRangeToken(normalizedFull) ?: return null

        val (start, end) = rangePair
        val rangeNormalized = if (start != null && end != null && start != end) {
            "$start-$end"
        } else {
            start ?: normalizedFull
        }

        // Check for potential suggestions and confidence penalty
        val (suggestion, penalty) = generateSuggestion(rangeNormalized)

        var confidence = 0.95
        if (penalty > 0) confidence -= penalty
        if (normalizedFull.length > 20) confidence -= 0.15
        if (start == null) confidence -= 0.2

        confidence = Math.max(0.1, Math.min(0.99, confidence))
        val requiresVerification = confidence < 0.85 || suggestion != null

        // Derive optional section name from text if available
        val sectionLine = lines.firstOrNull { line ->
            val norm = line.uppercase()
            !norm.contains(DASH_REGEX) && norm.length >= 3 && norm != start && norm != end
        }

        return ShelfDetection(
            type = "shelf_label",
            rawText = rawText.trim(),
            normalizedText = rangeNormalized,
            rangeStart = start,
            rangeEnd = end,
            section = sectionLine,
            confidence = (confidence * 100).toInt() / 100.0,
            boundingBox = boundingBox,
            suggestion = suggestion,
            requiresVerification = requiresVerification
        )
    }

    private fun runFallbackWholeImageOcr(bmp: Bitmap, visionText: Text): ShelfDetection? {
        val fullText = visionText.text
        if (fullText.isBlank()) return null

        val normalized = normalizeText(fullText)
        val rangePair = parseRangeToken(normalized) ?: return null

        val (start, end) = rangePair
        val rangeNormalized = if (start != null && end != null && start != end) "$start-$end" else (start ?: normalized)
        val (suggestion, penalty) = generateSuggestion(rangeNormalized)

        val confidence = Math.max(0.4, 0.75 - penalty)
        val bb = ShelfBoundingBox(0, 0, bmp.width, bmp.height)

        return ShelfDetection(
            type = "shelf_label",
            rawText = fullText.take(60),
            normalizedText = rangeNormalized,
            rangeStart = start,
            rangeEnd = end,
            section = null,
            confidence = confidence,
            boundingBox = bb,
            suggestion = suggestion,
            requiresVerification = true
        )
    }

    private fun loadOriginalBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)

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
        return BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
    }
}
