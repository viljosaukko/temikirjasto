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


    val alwaysFilter: String
        get() = preferences.getString(
            KEY_ALWAYS_FILTER,
            DEFAULT_ALWAYS_FILTER
        ) ?: DEFAULT_ALWAYS_FILTER


    /**
     * The exact library / branch name displayed by Finna.
     *
     * This is used when finding the correct shelf for
     * the "Vie hyllylle" button.
     */
    val libraryBranchName: String
        get() = preferences.getString(
            KEY_LIBRARY_BRANCH_NAME,
            DEFAULT_LIBRARY_BRANCH_NAME
        ) ?: DEFAULT_LIBRARY_BRANCH_NAME


    fun update(
        websiteUrl: String,
        alwaysFilter: String,
        libraryBranchName: String
    ) {

        preferences.edit()
            .putString(
                KEY_WEBSITE_URL,
                websiteUrl
            )
            .putString(
                KEY_ALWAYS_FILTER,
                alwaysFilter
            )
            .putString(
                KEY_LIBRARY_BRANCH_NAME,
                libraryBranchName
            )
            .apply()
    }


    /**
     * Backwards-compatible update function.
     *
     * Existing code that only updates the URL and filter
     * will continue to work.
     */
    fun update(
        websiteUrl: String,
        alwaysFilter: String
    ) {

        preferences.edit()
            .putString(
                KEY_WEBSITE_URL,
                websiteUrl
            )
            .putString(
                KEY_ALWAYS_FILTER,
                alwaysFilter
            )
            .apply()
    }


    companion object {

        const val KEY_WEBSITE_URL =
            "website_url"


        const val KEY_ALWAYS_FILTER =
            "always_filter"


        const val KEY_LIBRARY_BRANCH_NAME =
            "library_branch_name"


        // Current Oulun keskustakirjasto Saari defaults.
        const val DEFAULT_WEBSITE_URL =
            "https://outi.finna.fi/Search/Results?lookfor=&type=AllFields"


        const val DEFAULT_ALWAYS_FILTER =
            "~building:\"2/Outi/OU/SA/\""


        /**
         * Must match the branch name shown on the Finna website.
         */
        const val DEFAULT_LIBRARY_BRANCH_NAME =
            "Oulun keskustakirjasto Saari"
    }
}