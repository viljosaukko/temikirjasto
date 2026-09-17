package com.kirjasto.kirjastobotti

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages shelf metadata persistence and simple ZIP import for shelf images.
 *
 * This is intentionally lightweight: JSON file storage under filesDir/shelves.json
 * and images under filesDir/shelf_images/.
 */
class ShelfRepository(val context: Context) {

    private val TAG = "ShelfRepository"
    private val shelvesFile = File(context.filesDir, "shelves.json")
    private val imagesDir = File(context.filesDir, "shelf_images")

    init {
        if (!imagesDir.exists()) imagesDir.mkdirs()
        if (!shelvesFile.exists()) shelvesFile.createNewFile()
        // Ensure file contains an empty array if empty
        if (shelvesFile.length() == 0L) shelvesFile.writeText("[]")
    }

    fun listShelves(): List<Shelf> {
        try {
            val text = shelvesFile.readText()
            val arr = JSONArray(text)
            val result = mutableListOf<Shelf>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(Shelf.fromJson(o))
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "listShelves: failed", e)
            return emptyList()
        }
    }

    fun saveShelves(shelves: List<Shelf>) {
        val arr = JSONArray()
        for (s in shelves) arr.put(s.toJson())
        shelvesFile.writeText(arr.toString(2))
    }

    fun saveDraft(shelf: Shelf): Shelf {
        val updated = shelf.copy(active = false, lastUpdated = System.currentTimeMillis())
        upsertShelf(updated)
        return updated
    }

    fun activateShelf(shelfId: String): Boolean {
        val shelves = listShelves().toMutableList()
        val idx = shelves.indexOfFirst { it.id == shelfId }
        if (idx < 0) return false
        val candidate = shelves[idx]
        if (!candidate.isReadyForActivation()) return false
        shelves[idx] = candidate.copy(active = true, lastUpdated = System.currentTimeMillis())
        saveShelves(shelves)
        return true
    }

    fun listActiveShelves(): List<Shelf> = listShelves().filter { it.active }

    fun listDraftShelves(): List<Shelf> = listShelves().filter { !it.active }

    /**
     * Extract images from a ZIP file (path provided) and store them under filesDir/shelf_images.
     * Returns list of saved image file paths.
     */
    fun importZip(zipPath: String): List<String> {
        val saved = mutableListOf<String>()
        val zipFile = File(zipPath)
        if (!zipFile.exists()) return saved

        ZipInputStream(zipFile.inputStream()).use { zis ->
            var ze: ZipEntry? = zis.nextEntry
            while (ze != null) {
                if (!ze.isDirectory) {
                    val name = File(ze.name).name
                    // Only process common image extensions
                    if (name.lowercase().endsWith(".jpg") || name.lowercase().endsWith(".jpeg") || name.lowercase().endsWith(".png")) {
                        val outFile = File(imagesDir, name)
                        FileOutputStream(outFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        saved.add(outFile.absolutePath)
                    }
                }
                ze = zis.nextEntry
            }
        }
        return saved
    }

    /**
     * Very small heuristic analyzer: derive a "range" (e.g., A, B-C) from the filename if possible.
     * For real OCR / ML analysis replace this with proper image analysis.
     */
    fun analyzeImagesGenerateShelves(imagePaths: List<String>): List<Shelf> {
        val existing = listShelves().toMutableList()
        val generated = analyzeImagesReturnNew(imagePaths)
        existing.addAll(generated)
        saveShelves(existing)
        return existing
    }

    /**
     * Non-persisting analysis: returns a list of detected Shelf objects for the given images
     * without saving them. Useful for presenting OCR results to the user for verification.
     */
    fun analyzeImagesReturnNew(imagePaths: List<String>): List<Shelf> {
        val existing = listShelves()
        var counter = existing.size + 1
        val existingIds = existing.map { it.id }.toMutableSet()
        val result = mutableListOf<Shelf>()

        for (path in imagePaths) {
            val file = File(path)
            val name = file.nameWithoutExtension
            var ocrText: String? = null

            // Run OCR when possible; fallback to filename heuristics if OCR is weak.
            try {
                ocrText = ImageOcr.recognizeTextBlocking(context, file)
            } catch (e: Exception) {
                Log.w(TAG, "OCR failed for ${file.name}", e)
            }

            val combinedText = buildString {
                append(name.replace("_", " "))
                if (!ocrText.isNullOrBlank()) {
                    append('\n')
                    append(ocrText)
                }
            }.uppercase()

            val rangeToken = findBestRangeToken(combinedText)
            val (start, end) = parseRange(rangeToken)

            val section = deriveSectionLabel(name, ocrText)
            val id = nextShelfId(name, counter, existingIds)
            existingIds.add(id)
            counter += 1

            val confidence = when {
                !ocrText.isNullOrBlank() && !rangeToken.isNullOrBlank() -> 0.98
                !rangeToken.isNullOrBlank() -> 0.9
                !ocrText.isNullOrBlank() -> 0.7
                else -> 0.4
            }

            val hint = buildSourceHint(
                fileName = file.name,
                section = section,
                rangeToken = rangeToken,
                ocrText = ocrText
            )

            val shelf = Shelf(
                id = id,
                section = section,
                rangeStart = start,
                rangeEnd = end,
                imagePath = file.absolutePath,
                confidence = confidence,
                lastUpdated = System.currentTimeMillis(),
                draftNotes = hint
            )

            result.add(shelf)
        }

        return result
    }

    fun upsertShelf(shelf: Shelf) {
        val shelves = listShelves().toMutableList()
        val idx = shelves.indexOfFirst { it.id == shelf.id }
        if (idx >= 0) {
            shelves[idx] = shelf
        } else {
            shelves.add(shelf)
        }
        saveShelves(shelves)
    }

    /**
     * Update a shelf's navigation coordinates and persist.
     */
    fun updateShelfLocation(shelfId: String, x: Double, y: Double, yaw: Double) {
        val shelves = listShelves().toMutableList()
        val idx = shelves.indexOfFirst { it.id == shelfId }
        if (idx >= 0) {
            val s = shelves[idx]
            val updated = s.copy(mapX = x, mapY = y, yaw = yaw, lastUpdated = System.currentTimeMillis())
            shelves[idx] = updated
            saveShelves(shelves)
        } else {
            // If this is a newly detected shelf that hasn't been saved yet, create a stub record now.
            val stub = Shelf(
                id = shelfId,
                section = null,
                rangeStart = null,
                rangeEnd = null,
                imagePath = null,
                mapX = x,
                mapY = y,
                yaw = yaw,
                confidence = 1.0,
                lastUpdated = System.currentTimeMillis()
            )
            upsertShelf(stub)
        }
    }

    private fun nextShelfId(
        fileStem: String,
        fallbackIndex: Int,
        existingIds: Set<String>
    ): String {
        val stem = fileStem
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(28)
            .ifBlank { "shelf" }

        var candidate = "shelf_${stem}"
        if (candidate !in existingIds) return candidate

        var suffix = fallbackIndex
        while (true) {
            candidate = "shelf_${stem}_$suffix"
            if (candidate !in existingIds) return candidate
            suffix += 1
        }
    }

    private fun findBestRangeToken(textUpper: String): String? {
        val patterns = listOf(
            Regex("""\b([A-ZÅÄÖ]{1,4})\s*[-–]\s*([A-ZÅÄÖ]{1,4})\b"""),
            Regex("""\b(\d{1,3}(?:[.,]\d{1,3})?)\s*[-–]\s*(\d{1,3}(?:[.,]\d{1,3})?)\b"""),
            Regex("""\b([A-ZÅÄÖ]{1,4})\b"""),
            Regex("""\b(\d{1,3}(?:[.,]\d{1,3})?)\b""")
        )

        for (regex in patterns) {
            val match = regex.find(textUpper)
            if (match != null) {
                return match.value
                    .replace("–", "-")
                    .replace("\\s+".toRegex(), "")
            }
        }
        return null
    }

    private fun parseRange(token: String?): Pair<String?, String?> {
        if (token.isNullOrBlank()) return Pair(null, null)
        val normalized = token.replace("–", "-")
        if (!normalized.contains("-")) {
            return Pair(normalized, normalized)
        }
        val parts = normalized.split("-", limit = 2)
        val start = parts.getOrNull(0)?.ifBlank { null }
        val end = parts.getOrNull(1)?.ifBlank { null }
        return Pair(start, end)
    }

    private fun deriveSectionLabel(fileStem: String, ocrText: String?): String {
        val ocrLine = ocrText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.length >= 4 && it.any { ch -> ch.isLetter() } }

        val fromOcr = ocrLine
            ?.replace(Regex("[^A-Za-zÅÄÖåäö0-9 ]"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()

        if (!fromOcr.isNullOrBlank()) {
            return fromOcr.take(40)
        }

        val fromName = fileStem
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        return if (fromName.isNotBlank()) fromName.take(40) else "kaunokirjallisuus"
    }

    private fun buildSourceHint(
        fileName: String,
        section: String?,
        rangeToken: String?,
        ocrText: String?
    ): String {
        val ocrSnippet = ocrText
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(80)
            ?: "(no ocr)"

        return "source=$fileName; sectionHint=${section ?: "-"}; rangeHint=${rangeToken ?: "-"}; ocr=$ocrSnippet"
    }
}
