package com.kirjasto.kirjastobotti

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast

import androidx.core.content.FileProvider

import org.json.JSONObject

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

import kotlin.math.max


data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String
)


object UpdateConfig {

    const val MANIFEST_URL =
        "https://github.com/viljosaukko/temikirjasto/releases/latest/download/update.json"


    const val PREFS_NAME =
        "kirjastobotti_updater"


    const val PREF_LAST_KNOWN_VERSION_CODE =
        "last_known_version_code"


    const val PREF_LAST_SUCCESSFUL_UPDATE_DATE =
        "last_successful_update_date"
}


class AppUpdater(
    private val activity: MainActivity
) {

    private val preferences =
        activity.getSharedPreferences(
            UpdateConfig.PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )


    private val executor =
        Executors.newSingleThreadExecutor()


    private val mainHandler =
        Handler(Looper.getMainLooper())


    private val dateFormatter =
        SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.US
        )


    @Volatile
    private var pendingInstallFile: File? =
        null


    @Volatile
    private var dailyCheckRunnable: Runnable? =
        null


    fun startAutomaticUpdateChecks() {

        recordSuccessfulUpdateIfVersionChanged()

        if (
            shouldCheckForUpdateToday()
        ) {

            val now =
                Calendar.getInstance()


            if (
                now.get(
                    Calendar.HOUR_OF_DAY
                ) >= 8
            ) {

                checkForUpdate()

            } else {

                scheduleNextDailyUpdateCheck()
            }

        } else {

            scheduleNextDailyUpdateCheck()
        }
    }


    fun stopAutomaticUpdateChecks() {

        dailyCheckRunnable?.let {

            mainHandler.removeCallbacks(it)
        }


        dailyCheckRunnable = null
    }


    fun checkForUpdate() {

        executor.execute {

            val update =
                fetchUpdateInfo()
                    ?: return@execute


            if (
                update.versionCode <=
                BuildConfig.VERSION_CODE
            ) {
                return@execute
            }


            mainHandler.post {

                startUpdate(update)
            }
        }
    }


    fun shouldCheckForUpdateToday(): Boolean {

        return lastSuccessfulUpdateDate() != todayKey()
    }


    fun resumePendingInstallIfPossible() {

        val file =
            pendingInstallFile
                ?: return


        if (
            canRequestPackageInstalls()
        ) {
            installUpdate(file)
        }
    }


    private fun scheduleNextDailyUpdateCheck() {

        val existingRunnable =
            dailyCheckRunnable


        if (
            existingRunnable != null
        ) {

            mainHandler.removeCallbacks(existingRunnable)
        }


        val now =
            Calendar.getInstance()


        val nextRun =
            Calendar.getInstance().apply {

                set(
                    Calendar.HOUR_OF_DAY,
                    8
                )
                set(
                    Calendar.MINUTE,
                    0
                )
                set(
                    Calendar.SECOND,
                    0
                )
                set(
                    Calendar.MILLISECOND,
                    0
                )

                if (
                    !after(now)
                ) {

                    add(
                        Calendar.DAY_OF_YEAR,
                        1
                    )
                }
            }


        val runnable =
            Runnable {

                if (
                    shouldCheckForUpdateToday()
                ) {

                    checkForUpdate()
                }


                scheduleNextDailyUpdateCheck()
            }


        dailyCheckRunnable =
            runnable


        mainHandler.postDelayed(
            runnable,
            nextRun.timeInMillis - now.timeInMillis
        )
    }


    private fun recordSuccessfulUpdateIfVersionChanged() {

        val storedVersionCode =
            preferences.getInt(
                UpdateConfig.PREF_LAST_KNOWN_VERSION_CODE,
                0
            )


        if (
            storedVersionCode == 0
        ) {

            preferences.edit()
                .putInt(
                    UpdateConfig.PREF_LAST_KNOWN_VERSION_CODE,
                    BuildConfig.VERSION_CODE
                )
                .apply()


            return
        }


        if (
            storedVersionCode ==
            BuildConfig.VERSION_CODE
        ) {

            return
        }


        preferences.edit()
            .putInt(
                UpdateConfig.PREF_LAST_KNOWN_VERSION_CODE,
                BuildConfig.VERSION_CODE
            )
            .putString(
                UpdateConfig.PREF_LAST_SUCCESSFUL_UPDATE_DATE,
                todayKey()
            )
            .apply()
    }


    private fun lastSuccessfulUpdateDate(): String {

        return preferences.getString(
            UpdateConfig.PREF_LAST_SUCCESSFUL_UPDATE_DATE,
            ""
        ).orEmpty()
    }


    private fun todayKey(): String {

        return dateFormatter.format(
            Date()
        )
    }


    private fun fetchUpdateInfo(): UpdateInfo? {

        val connection =
            URL(UpdateConfig.MANIFEST_URL)
                .openConnection() as HttpURLConnection


        return try {

            connection.connectTimeout =
                10000

            connection.readTimeout =
                10000

            connection.instanceFollowRedirects =
                true

            connection.requestMethod =
                "GET"

            connection.setRequestProperty(
                "Accept",
                "application/json"
            )


            if (
                connection.responseCode !in 200..299
            ) {
                return null
            }


            val body =
                connection.inputStream
                    .bufferedReader()
                    .use {
                        it.readText()
                    }


            val json =
                JSONObject(body)


            UpdateInfo(
                versionCode =
                    json.getInt("versionCode"),
                versionName =
                    json.optString(
                        "versionName",
                        json.getInt("versionCode").toString()
                    ),
                apkUrl =
                    json.getString("apkUrl"),
                sha256 =
                    json.optString(
                        "sha256",
                        ""
                    ).trim(),
                notes =
                    json.optString(
                        "notes",
                        ""
                    ).trim()
            )

        } catch (
            _: Exception
        ) {

            null

        } finally {

            connection.disconnect()
        }
    }


    private fun startUpdate(
        update: UpdateInfo
    ) {

        if (
            activity.isFinishing ||
            activity.isDestroyed
        ) {
            return
        }


        Toast.makeText(
            activity,
            "Update found. Updating...",
            Toast.LENGTH_LONG
        ).show()


        downloadAndInstall(update)
    }


    private fun downloadAndInstall(
        update: UpdateInfo
    ) {

        executor.execute {

            try {

                val apkFile =
                    downloadApk(update)


                pendingInstallFile =
                    apkFile


                mainHandler.post {

                    if (
                        canRequestPackageInstalls()
                    ) {
                        installUpdate(apkFile)
                    } else {
                        requestInstallPermission()
                    }
                }

            } catch (
                e: Exception
            ) {

                mainHandler.post {

                    Toast.makeText(
                        activity,
                        "Päivityksen lataus epäonnistui: ${e.message ?: "tuntematon virhe"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    private fun downloadApk(
        update: UpdateInfo
    ): File {

        val updatesDir =
            File(
                activity.cacheDir,
                "updates"
            )


        if (
            !updatesDir.exists()
        ) {
            updatesDir.mkdirs()
        }


        val apkFile =
            File(
                updatesDir,
                "update-${update.versionCode}.apk"
            )


        if (
            apkFile.exists()
        ) {
            apkFile.delete()
        }


        val connection =
            URL(update.apkUrl)
                .openConnection() as HttpURLConnection


        try {

            connection.connectTimeout =
                10000

            connection.readTimeout =
                20000

            connection.instanceFollowRedirects =
                true

            connection.requestMethod =
                "GET"

            if (
                connection.responseCode !in 200..299
            ) {
                throw IOException(
                    "HTTP ${connection.responseCode}"
                )
            }


            val expectedSha256 =
                update.sha256.lowercase()


            val digest =
                if (
                    expectedSha256.isNotBlank()
                ) {
                    MessageDigest.getInstance("SHA-256")
                } else {
                    null
                }


            connection.inputStream.use { input ->

                FileOutputStream(apkFile).use { output ->

                    val buffer =
                        ByteArray(16 * 1024)


                    while (true) {

                        val read =
                            input.read(buffer)


                        if (
                            read < 0
                        ) {
                            break
                        }


                        if (
                            read == 0
                        ) {
                            continue
                        }


                        output.write(
                            buffer,
                            0,
                            read
                        )


                        digest?.update(
                            buffer,
                            0,
                            read
                        )
                    }


                    output.flush()
                }
            }


            if (
                digest != null &&
                expectedSha256.isNotBlank()
            ) {

                val actualSha256 =
                    digest.digest()
                        .joinToString("") {
                            "%02x".format(it)
                        }


                if (
                    actualSha256 != expectedSha256
                ) {
                    throw IOException(
                        "SHA-256 mismatch"
                    )
                }
            }


            return apkFile

        } catch (
            e: Exception
        ) {

            if (
                apkFile.exists()
            ) {
                apkFile.delete()
            }


            throw e

        } finally {

            connection.disconnect()
        }
    }


    private fun installUpdate(
        apkFile: File
    ) {

        pendingInstallFile =
            apkFile


        val uri =
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )


        val intent =
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }


        if (
            intent.resolveActivity(activity.packageManager) != null
        ) {
            pendingInstallFile =
                null

            activity.startActivity(intent)
        } else {
            Toast.makeText(
                activity,
                "Päivityksen asenninta ei löytynyt.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun requestInstallPermission() {

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ) {
            return
        }


        Toast.makeText(
            activity,
            "Salli sovelluksen asentaa päivityksiä.",
            Toast.LENGTH_LONG
        ).show()


        val intent =
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }


        activity.startActivity(intent)
    }


    private fun canRequestPackageInstalls(): Boolean {

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O
        ) {
            return true
        }


        return activity.packageManager.canRequestPackageInstalls()
    }
}
