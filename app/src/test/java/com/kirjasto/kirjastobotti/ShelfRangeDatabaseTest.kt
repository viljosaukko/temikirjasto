package com.kirjasto.kirjastobotti

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShelfRangeDatabaseTest {

    private lateinit var fakeContext: FakeContext
    private lateinit var database: ShelfRangeDatabase
    private lateinit var setupPrefs: ShelfSetupPreferences

    @Before
    fun setUp() {
        fakeContext = FakeContext()
        database = ShelfRangeDatabase(fakeContext)
        setupPrefs = ShelfSetupPreferences(fakeContext)
    }

    @Test
    fun upsertCreatesShelfWithPosition() {
        val shelf = database.upsert("AIK84.2A-CAN", 1.25, -3.50, 45.0)
        assertEquals("AIK84.2A-CAN", shelf.text)
        assertEquals(1.25, shelf.mapX!!, 0.001)
        assertEquals(-3.50, shelf.mapY!!, 0.001)
        assertEquals(45.0, shelf.yaw!!, 0.001)

        val list = database.list()
        assertEquals(1, list.size)
        assertEquals(shelf.id, list[0].id)
    }

    @Test
    fun updateTextPreservesCoordinatesWithoutPositioning() {
        val original = database.upsert("AIK84.2A-CAN", 2.0, 3.0, 90.0)
        assertEquals(2.0, original.mapX!!, 0.001)
        assertEquals(3.0, original.mapY!!, 0.001)
        assertEquals(90.0, original.yaw!!, 0.001)

        // Rename the shelf to a different valid range
        val updated = database.updateText(original.id, "AIK84.2CON-D")
        assertNotNull(updated)
        assertEquals("AIK84.2CON-D", updated!!.text)
        // Coordinates MUST remain unchanged
        assertEquals(2.0, updated.mapX!!, 0.001)
        assertEquals(3.0, updated.mapY!!, 0.001)
        assertEquals(90.0, updated.yaw!!, 0.001)

        val fetched = database.get(original.id)
        assertEquals("AIK84.2CON-D", fetched?.text)
        assertEquals(2.0, fetched?.mapX!!, 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun updateTextRejectsInvalidShelfRange() {
        val original = database.upsert("AIK84.2A-CAN", 1.0, 1.0, 0.0)
        database.updateText(original.id, "INVALID_SHELF_FORMAT_XYZ")
    }

    @Test
    fun updateCoordinatesPreservesText() {
        val original = database.upsert("AIK84.2A-CAN", 1.0, 1.0, 0.0)

        val updated = database.updateCoordinates(original.id, 5.5, 6.6, 180.0)
        assertNotNull(updated)
        assertEquals("AIK84.2A-CAN", updated!!.text)
        assertEquals(5.5, updated.mapX!!, 0.001)
        assertEquals(6.6, updated.mapY!!, 0.001)
        assertEquals(180.0, updated.yaw!!, 0.001)
    }

    @Test
    fun deleteRemovesShelf() {
        val shelf1 = database.upsert("AIK84.2A-CAN", 1.0, 1.0, 0.0)
        val shelf2 = database.upsert("AIK84.2CON-D", 2.0, 2.0, 0.0)
        assertEquals(2, database.list().size)

        val deleted = database.delete(shelf1.id)
        assertTrue(deleted)
        assertEquals(1, database.list().size)
        assertEquals(shelf2.id, database.list()[0].id)
    }

    @Test
    fun shortcutPreferencesDefaultsAndCustomAddRemove() {
        val defaults = setupPrefs.getShortcuts()
        assertTrue(defaults.contains("AIK"))
        assertTrue(defaults.contains("84.2"))

        // Add custom shortcut
        val afterAdd = setupPrefs.addShortcut("DEK")
        assertTrue(afterAdd.contains("DEK"))
        assertTrue(setupPrefs.getShortcuts().contains("DEK"))

        // Remove shortcut
        val afterRemove = setupPrefs.removeShortcut("DEK")
        assertFalse(afterRemove.contains("DEK"))
        assertFalse(setupPrefs.getShortcuts().contains("DEK"))

        // Reset defaults
        val reset = setupPrefs.resetDefaults()
        assertEquals(ShelfSetupPreferences.DEFAULT_SHORTCUTS, reset)
    }
}

/**
 * Minimal in-memory FakeContext using ContextWrapper and FakeSharedPreferences for fast JVM unit testing.
 */
class FakeContext : ContextWrapper(null) {
    private val prefsMap = mutableMapOf<String, FakeSharedPreferences>()

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        val key = name ?: "default"
        return prefsMap.getOrPut(key) { FakeSharedPreferences() }
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) pending[key] = values
            return this
        }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) pending[key] = value
            return this
        }
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) pending[key] = null
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            pending.forEach { (k, v) ->
                if (v == null) values.remove(k) else values[k] = v
            }
            pending.clear()
            clear = false
        }
    }
}
