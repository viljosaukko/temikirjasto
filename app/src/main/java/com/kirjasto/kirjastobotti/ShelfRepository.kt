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
        val nextIndex = (existing.size + 1)
        var counter = nextIndex
        val result = mutableListOf<Shelf>()

        for (path in imagePaths) {
            val file = File(path)
            val name = file.nameWithoutExtension
            // Try to find patterns like A, B-C, L-N etc.
            val rangeRegex = Regex("([A-ZÅÄÖ]+(?:-[A-ZÅÄÖ]+)?)")

            // First, try filename heuristics
            val match = rangeRegex.find(name.replace("_"," ").uppercase())
            var range = match?.groups?.get(1)?.value
            var ocrText: String? = null

            // If possible, run OCR on the image to look for shelf signs (more reliable)
            try {
                ocrText = ImageOcr.recognizeTextBlocking(context, file)
            } catch (e: Exception) {
                // OCR failed; continue with filename heuristic
            }

            if (!ocrText.isNullOrBlank()) {
                // merge OCR result with filename heuristic: look for A or B-C patterns in OCR text
                val ocrMatch = rangeRegex.find(ocrText.uppercase())
                if (ocrMatch != null) {
                    range = ocrMatch.groups[1]?.value ?: range
                }
            }

            val (start, end) = if (range != null && range.contains("-")) {
                val parts = range.split("-")
                Pair(parts[0], parts[1])
            } else if (range != null) {
                Pair(range, range)
            } else Pair(null, null)

            val section = "kaunokirjallisuus" // default for prototype
            val id = String.format("shelf_%03d", counter)
            counter += 1

            val confidence = when {
                !ocrText.isNullOrBlank() && range != null -> 0.98
                range != null -> 0.9
                !ocrText.isNullOrBlank() -> 0.7
                else -> 0.4
            }

            val shelf = Shelf(
                id = id,
                section = section,
                rangeStart = start,
                rangeEnd = end,
                imagePath = file.absolutePath,
                confidence = confidence,
                lastUpdated = System.currentTimeMillis()
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
}
