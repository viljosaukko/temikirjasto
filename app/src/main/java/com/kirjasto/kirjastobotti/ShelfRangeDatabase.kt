package com.kirjasto.kirjastobotti

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Persistent storage for manually configured physical shelf ranges. */
class ShelfRangeDatabase(context: Context) {
    private val preferences = context.getSharedPreferences(
        "kirjastobotti_shelf_ranges",
        Context.MODE_PRIVATE
    )

    fun list(): List<ShelfRange> {
        val raw = preferences.getString(KEY_RANGES, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        ShelfRange(
                            id = o.optString("id"),
                            text = o.optString("text"),
                            mapX = if (o.has("mapX")) o.optDouble("mapX") else null,
                            mapY = if (o.has("mapY")) o.optDouble("mapY") else null,
                            yaw = if (o.has("yaw")) o.optDouble("yaw") else null,
                            preclass = o.optString("preclass").trim().ifBlank { null }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsert(
        text: String,
        x: Double,
        y: Double,
        yaw: Double,
        id: String? = null,
        preclass: String? = null,
        setPreclass: Boolean = false
    ): ShelfRange {
        val normalized = text.trim().uppercase().replace('–', '-').replace('—', '-')
        require(ShelfRangeParser.parseRange(normalized) != null) {
            "Invalid shelf range: $text"
        }
        val ranges = list().toMutableList()
        val actualId = id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val index = ranges.indexOfFirst { it.id == actualId }
        val existing = index.takeIf { it >= 0 }?.let { ranges[it] }
        val resolvedPreclass = if (setPreclass) {
            ShelfRangeParser.normalizePreclassTags(preclass).joinToString(", ").ifBlank { null }
        } else {
            existing?.normalizedPreclass
        }
        val shelf = ShelfRange(actualId, normalized, x, y, yaw, resolvedPreclass)
        if (index >= 0) ranges[index] = shelf else ranges.add(shelf)
        save(ranges)
        return shelf
    }

    fun delete(id: String): Boolean {
        val ranges = list().toMutableList()
        val removed = ranges.removeAll { it.id == id }
        if (removed) save(ranges)
        return removed
    }

    /**
     * Updates the text/name and optionally preclass of an existing shelf without modifying its positioning.
     */
    fun updateShelf(
        id: String,
        newText: String,
        preclass: String? = null,
        setPreclass: Boolean = true
    ): ShelfRange? {
        val normalized = newText.trim().uppercase().replace('–', '-').replace('—', '-')
        require(ShelfRangeParser.parseRange(normalized) != null) {
            "Invalid shelf range: $newText"
        }
        val ranges = list().toMutableList()
        val index = ranges.indexOfFirst { it.id == id }
        if (index < 0) return null
        val existing = ranges[index]
        val resolvedPreclass = if (setPreclass) {
            ShelfRangeParser.normalizePreclassTags(preclass).joinToString(", ").ifBlank { null }
        } else {
            existing.normalizedPreclass
        }
        val updated = existing.copy(text = normalized, preclass = resolvedPreclass)
        ranges[index] = updated
        save(ranges)
        return updated
    }

    /**
     * Updates the text/name of an existing shelf without modifying its positioning.
     */
    fun updateText(id: String, newText: String): ShelfRange? {
        return updateShelf(id, newText, setPreclass = false)
    }

    /**
     * Updates the coordinates (x, y, yaw) of an existing shelf without altering its text.
     */
    fun updateCoordinates(id: String, x: Double, y: Double, yaw: Double): ShelfRange? {
        val ranges = list().toMutableList()
        val index = ranges.indexOfFirst { it.id == id }
        if (index < 0) return null
        val existing = ranges[index]
        val updated = existing.copy(mapX = x, mapY = y, yaw = yaw)
        ranges[index] = updated
        save(ranges)
        return updated
    }

    fun get(id: String): ShelfRange? {
        return list().firstOrNull { it.id == id }
    }

    fun findForFinnaShelf(rawShelf: String): ShelfRange? {
        val ranges = list()
        val target = ShelfRangeParser.normalizeFinnaShelf(rawShelf) ?: return null
        val bookPreclass = ShelfRangeParser.resolveExistingPreclass(rawShelf, ranges)
        return ShelfRangeParser.selectShelf(target, bookPreclass, ranges)
    }

    private fun save(ranges: List<ShelfRange>) {
        val array = JSONArray()
        ranges.forEach { shelf ->
            array.put(
                JSONObject().apply {
                    put("id", shelf.id)
                    put("text", shelf.text)
                    if (shelf.mapX != null) put("mapX", shelf.mapX)
                    if (shelf.mapY != null) put("mapY", shelf.mapY)
                    if (shelf.yaw != null) put("yaw", shelf.yaw)
                    val preclass = shelf.normalizedPreclass
                    if (preclass != null) put("preclass", preclass)
                }
            )
        }
        preferences.edit().putString(KEY_RANGES, array.toString()).apply()
    }

    companion object {
        private const val KEY_RANGES = "ranges"
    }
}
