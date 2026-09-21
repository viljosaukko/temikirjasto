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
    val contents: String? = null,
    val mapX: Double? = null,
    val mapY: Double? = null,
    val yaw: Double? = null,
    val confidence: Double = 1.0,
    val lastUpdated: Long = System.currentTimeMillis(),
    val active: Boolean = true,
    val draftNotes: String? = null
) {
    fun isReadyForActivation(): Boolean {
        val hasSection = !section.isNullOrBlank()
        val hasShelfDescription = !rangeStart.isNullOrBlank() ||
            !rangeEnd.isNullOrBlank() || !contents.isNullOrBlank()
        val hasPosition = mapX != null && mapY != null && yaw != null
        return hasSection && hasShelfDescription && hasPosition
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("section", section)
        o.put("rangeStart", rangeStart)
        o.put("rangeEnd", rangeEnd)
        o.put("contents", contents)
        o.put("mapX", mapX)
        o.put("mapY", mapY)
        o.put("yaw", yaw)
        o.put("confidence", confidence)
        o.put("lastUpdated", lastUpdated)
        o.put("active", active)
        o.put("draftNotes", draftNotes)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Shelf {
            return Shelf(
                id = o.optString("id"),
                section = if (o.has("section")) o.optString("section") else null,
                rangeStart = if (o.has("rangeStart")) o.optString("rangeStart") else null,
                rangeEnd = if (o.has("rangeEnd")) o.optString("rangeEnd") else null,
                contents = if (o.has("contents")) o.optString("contents") else null,
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
                draftNotes = if (o.has("draftNotes")) o.optString("draftNotes") else null
            )
        }
    }
}
