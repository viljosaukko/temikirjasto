package com.kirjasto.kirjastobotti

import android.content.Context

/**
 * Library-specific configuration.
 *
 * This is stored in SharedPreferences, so updating the APK does not
 * overwrite the configuration as long as the package/signing identity
 * stays the same.
 */
class LibraryConfig(context: Context) {

    private val preferences =
        context.getSharedPreferences(
            "kirjastobotti_library_config",
            Context.MODE_PRIVATE
        )


    val websiteUrl: String
        get() = preferences.getString(
            KEY_WEBSITE_URL,
            DEFAULT_WEBSITE_URL
        ) ?: DEFAULT_WEBSITE_URL


    fun update(websiteUrl: String) {

        preferences.edit()
            .putString(
                KEY_WEBSITE_URL,
                websiteUrl
            )
            .apply()
    }


    companion object {

        const val KEY_WEBSITE_URL =
            "website_url"


        const val DEFAULT_WEBSITE_URL =
            "https://outi.finna.fi/Search/Results?lookfor=&type=AllFields"

        // Kirjastobotti is installed at this branch only.
        const val SAARI_FILTER =
            "~building:\"2/Outi/OU/SA/\""

        const val SAARI_BRANCH_NAME =
            "Oulun keskustakirjasto Saari"
    }
}
