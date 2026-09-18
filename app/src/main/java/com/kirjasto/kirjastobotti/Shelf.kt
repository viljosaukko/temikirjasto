package com.kirjasto.kirjastobotti

import org.json.JSONObject

/**
 * Simple shelf metadata model.
 * Serialized to a JSON file by ShelfRepository.
 */

data class Shelf(
    val id: String,
    val section: String?,
    val rangeStart: String?,
    val rangeEnd: String?,
    val imagePath: String?,
    val mapX: Double? = null,
    val mapY: Double? = null,
    val yaw: Double? = null,
    val confidence: Double = 1.0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val active: Boolean = true,
    val draftNotes: String? = null,
    val rawText: String? = null,
    val normalizedText: String? = null,
    val boundingBox: ShelfBoundingBox? = null,
    val ocrSuggestion: String? = null,
    val requiresVerification: Boolean = false
) {
    fun isReadyForActivation(): Boolean {
        val hasSection = !section.isNullOrBlank()
        val hasRange = !rangeStart.isNullOrBlank() || !rangeEnd.isNullOrBlank()
        val hasPosition = mapX != null && mapY != null && yaw != null
        return hasSection && hasRange && hasPosition && !imagePath.isNullOrBlank()
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("section", section)
        o.put("rangeStart", rangeStart)
        o.put("rangeEnd", rangeEnd)
        o.put("imagePath", imagePath)
        o.put("mapX", mapX)
        o.put("mapY", mapY)
        o.put("yaw", yaw)
        o.put("confidence", confidence)
        o.put("lastUpdated", lastUpdated)
        o.put("active", active)
        o.put("draftNotes", draftNotes)
        if (rawText != null) o.put("rawText", rawText)
        if (normalizedText != null) o.put("normalizedText", normalizedText)
        if (boundingBox != null) o.put("boundingBox", boundingBox.toJson())
        if (ocrSuggestion != null) o.put("ocrSuggestion", ocrSuggestion)
        o.put("requiresVerification", requiresVerification)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Shelf {
            val bbObj = o.optJSONObject("boundingBox")
            return Shelf(
                id = o.optString("id"),
                section = if (o.has("section")) o.optString("section") else null,
                rangeStart = if (o.has("rangeStart")) o.optString("rangeStart") else null,
                rangeEnd = if (o.has("rangeEnd")) o.optString("rangeEnd") else null,
                imagePath = if (o.has("imagePath")) o.optString("imagePath") else null,
                mapX = if (o.has("mapX")) {
                    val v = o.optDouble("mapX")
                    if (v.isNaN()) null else v
                } else null,
                mapY = if (o.has("mapY")) {
                    val v = o.optDouble("mapY")
                    if (v.isNaN()) null else v
                } else null,
                yaw = if (o.has("yaw")) {
                    val v = o.optDouble("yaw")
                    if (v.isNaN()) null else v
                } else null,
                confidence = o.optDouble("confidence", 1.0),
                lastUpdated = o.optLong("lastUpdated", System.currentTimeMillis()),
                active = if (o.has("active")) o.optBoolean("active", true) else true,
                draftNotes = if (o.has("draftNotes")) o.optString("draftNotes") else null,
                rawText = if (o.has("rawText")) o.optString("rawText") else null,
                normalizedText = if (o.has("normalizedText")) o.optString("normalizedText") else null,
                boundingBox = ShelfBoundingBox.fromJson(bbObj),
                ocrSuggestion = if (o.has("ocrSuggestion")) o.optString("ocrSuggestion") else null,
                requiresVerification = o.optBoolean("requiresVerification", false)
            )
        }
    }
}
