package com.kirjasto.kirjastobotti

import android.app.AlertDialog
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


    const val PREF_LAST_PROMPTED_VERSION =
        "last_prompted_version"
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


    @Volatile
    private var pendingInstallFile: File? =
        null


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


            val lastPromptedVersion =
                preferences.getInt(
                    UpdateConfig.PREF_LAST_PROMPTED_VERSION,
                    0
                )


            if (
                update.versionCode <=
                lastPromptedVersion
            ) {
                return@execute
            }


            mainHandler.post {

                showUpdateDialog(update)
            }
        }
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


    private fun showUpdateDialog(
        update: UpdateInfo
    ) {

        if (
            activity.isFinishing ||
            activity.isDestroyed
        ) {
            return
        }


        preferences.edit()
            .putInt(
                UpdateConfig.PREF_LAST_PROMPTED_VERSION,
                update.versionCode
            )
            .apply()


        val message =
            buildString {

                append(
                    "Uusi versio on saatavilla."
                )

                appendLine()
                appendLine()
                append(
                    "Nykyinen: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                )
                appendLine()
                append(
                    "Uusi: ${update.versionName} (${update.versionCode})"
                )

                if (
                    update.notes.isNotBlank()
                ) {
                    appendLine()
                    appendLine()
                    append(update.notes)
                }
            }


        AlertDialog.Builder(activity)
            .setTitle("Päivitys")
            .setMessage(message)
            .setCancelable(false)
            .setNegativeButton("Myöhemmin") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Päivitä") { dialog, _ ->
                dialog.dismiss()
                downloadAndInstall(update)
            }
            .show()
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
