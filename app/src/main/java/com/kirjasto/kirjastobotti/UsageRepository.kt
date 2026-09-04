package com.kirjasto.kirjastobotti

import android.content.Context
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UsageError(
    val message: String,
    val count: Int
)

data class UsageSnapshot(
    val requestsToday: Int,
    val requestsAllTime: Int,
    val failedRequests: Int,
    val errors: List<UsageError>
)

class UsageRepository(context: Context) {

    private val preferences = context.getSharedPreferences(
        "kirjastobotti_usage",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun recordRequest(success: Boolean, error: String? = null) {
        val editor = preferences.edit()
        val todayKey = dayKey()
        editor.putInt(KEY_ALL_TIME, preferences.getInt(KEY_ALL_TIME, 0) + 1)
        editor.putInt(todayKey, preferences.getInt(todayKey, 0) + 1)

        if (!success) {
            recordFailureIn(editor, error)
        }

        editor.apply()
    }

    @Synchronized
    fun recordFailure(error: String?) {
        val editor = preferences.edit()
        recordFailureIn(editor, error)
        editor.apply()
    }

    @Synchronized
    fun snapshot(): UsageSnapshot {
        val errors = preferences.all
            .filterKeys { it.startsWith(KEY_ERROR_PREFIX) }
            .mapNotNull { (key, value) ->
                val message = decodeError(key.removePrefix(KEY_ERROR_PREFIX))
                val count = value as? Int ?: return@mapNotNull null
                UsageError(message, count)
            }
            .sortedWith(compareByDescending<UsageError> { it.count }.thenBy { it.message })

        return UsageSnapshot(
            requestsToday = preferences.getInt(dayKey(), 0),
            requestsAllTime = preferences.getInt(KEY_ALL_TIME, 0),
            failedRequests = preferences.getInt(KEY_FAILED, 0),
            errors = errors
        )
    }

    private fun dayKey(): String = KEY_DAY_PREFIX + SimpleDateFormat(
        "yyyy-MM-dd",
        Locale.ROOT
    ).format(Date())

    private fun errorKey(message: String): String = KEY_ERROR_PREFIX + Base64.encodeToString(
        message.toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE
    )

    private fun decodeError(value: String): String = try {
        String(Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        "Tuntematon virhe"
    }

    private fun recordFailureIn(editor: android.content.SharedPreferences.Editor, error: String?) {
        editor.putInt(KEY_FAILED, preferences.getInt(KEY_FAILED, 0) + 1)
        val message = error?.trim().takeUnless { it.isNullOrEmpty() }
            ?: "Tuntematon virhe"
        val key = errorKey(message)
        editor.putInt(key, preferences.getInt(key, 0) + 1)
    }

    companion object {
        private const val KEY_ALL_TIME = "requests_all_time"
        private const val KEY_FAILED = "failed_requests"
        private const val KEY_DAY_PREFIX = "requests_day_"
        private const val KEY_ERROR_PREFIX = "error_"
    }
}