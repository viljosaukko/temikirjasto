package com.kirjasto.kirjastobotti

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

/** Stores manually collected shelf details and Temi navigation positions. */
class ShelfRepository(val context: Context, private val usageRepository: UsageRepository? = null) {
    private val shelvesFile = File(context.filesDir, "shelves.json")

    init {
        if (!shelvesFile.exists()) shelvesFile.createNewFile()
        if (shelvesFile.length() == 0L) shelvesFile.writeText("[]")
    }

    fun reportFailure(source: String, throwable: Throwable) {
        val cause = rootCause(throwable)
        val detail = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
        Log.e(TAG, "$source: $detail", throwable)
        usageRepository?.recordFailure("[$source] $detail")
    }

    fun listShelves(): List<Shelf> = try {
        val array = JSONArray(shelvesFile.readText())
        List(array.length()) { index -> Shelf.fromJson(array.getJSONObject(index)) }
    } catch (e: Exception) {
        reportFailure("Shelf data read", e)
        emptyList()
    }

    fun listActiveShelves(): List<Shelf> = listShelves().filter { it.active }
    fun listDraftShelves(): List<Shelf> = listShelves().filterNot { it.active }

    fun upsertShelf(shelf: Shelf) {
        val shelves = listShelves().toMutableList()
        val index = shelves.indexOfFirst { it.id == shelf.id }
        if (index >= 0) shelves[index] = shelf else shelves.add(shelf)
        saveShelves(shelves)
    }

    fun deleteShelf(shelfId: String) = saveShelves(listShelves().filterNot { it.id == shelfId })

    fun activateShelf(shelfId: String): Boolean {
        val shelf = listShelves().firstOrNull { it.id == shelfId } ?: return false
        if (!shelf.isReadyForActivation()) return false
        upsertShelf(shelf.copy(active = true, lastUpdated = System.currentTimeMillis()))
        return true
    }

    fun updateShelfLocation(shelfId: String, x: Double, y: Double, yaw: Double) {
        val shelf = listShelves().firstOrNull { it.id == shelfId } ?: return
        upsertShelf(shelf.copy(mapX = x, mapY = y, yaw = yaw, active = false, lastUpdated = System.currentTimeMillis()))
    }

    private fun saveShelves(shelves: List<Shelf>) {
        val array = JSONArray()
        shelves.forEach { array.put(it.toJson()) }
        shelvesFile.writeText(array.toString(2))
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }

    private companion object { const val TAG = "ShelfRepository" }
}
