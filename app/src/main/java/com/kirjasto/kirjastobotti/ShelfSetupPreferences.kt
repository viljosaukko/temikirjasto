package com.kirjasto.kirjastobotti

import android.content.Context
import org.json.JSONArray

/**
 * Manages user-defined shortcut buttons for the on-robot shelf setup mode.
 */
class ShelfSetupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getShortcuts(): List<String> {
        val raw = prefs.getString(KEY_SHORTCUTS, null) ?: return DEFAULT_SHORTCUTS
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.optString(i)?.trim().orEmpty()
                if (item.isNotBlank()) list.add(item)
            }
            if (list.isEmpty()) DEFAULT_SHORTCUTS else list
        } catch (_: Exception) {
            DEFAULT_SHORTCUTS
        }
    }

    fun addShortcut(shortcut: String): List<String> {
        val cleaned = shortcut.trim().uppercase()
        if (cleaned.isBlank()) return getShortcuts()
        val current = getShortcuts().toMutableList()
        if (!current.contains(cleaned)) {
            current.add(cleaned)
            save(current)
        }
        return current
    }

    fun removeShortcut(shortcut: String): List<String> {
        val current = getShortcuts().toMutableList()
        current.removeAll { it.equals(shortcut.trim(), ignoreCase = true) }
        save(current)
        return current
    }

    fun resetDefaults(): List<String> {
        save(DEFAULT_SHORTCUTS)
        return DEFAULT_SHORTCUTS
    }

    private fun save(shortcuts: List<String>) {
        val array = JSONArray()
        shortcuts.forEach { array.put(it) }
        prefs.edit().putString(KEY_SHORTCUTS, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kirjastobotti_shelf_setup_prefs"
        private const val KEY_SHORTCUTS = "setup_shortcuts"

        val DEFAULT_SHORTCUTS = listOf("AIK", "84.2", "LAP", "NUO", "-", ".")
    }
}
