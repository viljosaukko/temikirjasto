package com.kirjasto.kirjastobotti

import android.content.Context
import android.util.Log

import com.robotemi.sdk.Robot

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

import java.nio.charset.StandardCharsets

import java.util.Collections

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import kotlin.math.abs


/**
 * LAN-only HTTP server for the temi admin panel.
 *
 * Manual movement:
 *
 * x = forward / backward
 * y = left / right
 */
class AdminServer(
    private val context: Context,
    private val robot: Robot,
    private val camera: CameraStreamer,
    private val usageRepository: UsageRepository
) {

    private val libraryConfig =
        LibraryConfig(context)

    private val shelfRangeDatabase =
        ShelfRangeDatabase(context)

    private val controlKeybindPrefs =
        context.getSharedPreferences(
            PREFS_CONTROL_KEYBINDS,
            Context.MODE_PRIVATE
        )


    companion object {

        private const val TAG =
            "KirjastobottiAdmin"

        const val PORT =
            8080

        private const val MAX_SETUP_UPLOAD_BYTES =
            25 * 1024 * 1024

        private const val MAX_SETUP_IMAGE_UPLOAD_BYTES =
            10 * 1024 * 1024

        private const val PREFS_CONTROL_KEYBINDS =
            "kirjastobotti_control_keybinds"
    }


    private var serverSocket:
            ServerSocket? =
        null


    private var acceptThread:
            Thread? =
        null


    private val workers =
        Executors.newCachedThreadPool()

    private val safety:
            ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor()

    private val requestTimestamps =
        Collections.synchronizedMap(
            mutableMapOf<String, MutableList<Long>>()
        )

    @Volatile
    private var lastCommandAt =
        0L

    @Volatile
    private var lastX =
        0f


    @Volatile
    private var lastY =
        0f


    private fun allowRequest(clientAddress: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = requestTimestamps.computeIfAbsent(clientAddress) { mutableListOf() }
        timestamps.removeAll { now - it > 60_000 }
        if (timestamps.size >= 60) {
            return false
        }
        timestamps.add(now)
        return true
    }

    fun start() {

        if (
            serverSocket != null
        ) {
            return
        }


        try {

            serverSocket =
                ServerSocket(
                    PORT,
                    16,
                    InetAddress.getByName(
                        "0.0.0.0"
                    )
                )


            acceptThread =
                Thread {

                    while (
                        !Thread.currentThread()
                            .isInterrupted
                    ) {

                        try {

                            val socket =
                                serverSocket
                                    ?.accept()
                                    ?: break


                            workers.execute {

                                handle(socket)
                            }

                        } catch (
                            e: Exception
                        ) {

                            if (
                                serverSocket != null
                            ) {

                                Log.e(
                                    TAG,
                                    "Accept failed",
                                    e
                                )
                            }

                            break
                        }
                    }

                }.apply {

                    name =
                        "Kirjastobotti-admin-accept"

                    start()
                }


            safety.scheduleAtFixedRate(
                {

                    if (
                        System.currentTimeMillis() -
                        lastCommandAt >
                        550L &&
                        (
                                abs(lastX) > 0.001f ||
                                        abs(lastY) > 0.001f
                                )
                    ) {

                        stopRobot()
                    }

                },
                250,
                250,
                TimeUnit.MILLISECONDS
            )


            Log.i(
                TAG,
                "Admin panel listening on port $PORT"
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Could not start admin server",
                e
            )

            stop()
        }
    }


    fun stop() {

        try {

            serverSocket?.close()

        } catch (
            _: Exception
        ) {
        }


        serverSocket =
            null


        acceptThread?.interrupt()

        acceptThread =
            null


        stopRobot()


        safety.shutdownNow()

        workers.shutdownNow()
    }


    private fun handle(
        socket: Socket
    ) {

        socket.use {

            try {

                it.soTimeout =
                    5000


                val input =
                    BufferedInputStream(
                        it.getInputStream()
                    )


                val requestLine =
                    readAsciiLine(input)
                        ?: return


                val headers =
                    mutableMapOf<String, String>()

                while (true) {
                    val line =
                        readAsciiLine(input)
                            ?: break

                    if (line.isEmpty()) {
                        break
                    }

                    val idx = line.indexOf(':')
                    if (idx <= 0) continue

                    val key =
                        line.substring(0, idx)
                            .trim()
                            .lowercase()

                    val value =
                        line.substring(idx + 1)
                            .trim()

                    headers[key] = value
                }


                val parts =
                    requestLine.split(
                        " "
                    )


                if (
                    parts.size < 2
                ) {

                    writeText(
                        it.getOutputStream(),
                        400,
                        "Bad Request"
                    )

                    return
                }


                val method =
                    parts[0]


                val rawTarget =
                    parts[1]

                val clientAddress = socket.inetAddress?.hostAddress ?: "unknown"
                if (!allowRequest(clientAddress)) {
                    writeText(
                        it.getOutputStream(),
                        429,
                        "{\"ok\":false,\"error\":\"rate limit exceeded\"}",
                        "application/json; charset=utf-8"
                    )
                    return
                }

                val target =
                    rawTarget.substringBefore(
                        '?'
                    )


                val query =
                    parseQuery(
                        rawTarget.substringAfter(
                            '?',
                            ""
                        )
                    )

                val contentLength =
                    headers["content-length"]
                        ?.toIntOrNull()
                        ?: 0

                val isSetupZipUpload =
                    method == "POST" &&
                            target == "/api/setup-upload-zip"

                val isSetupImageUpload =
                    method == "POST" &&
                            target == "/api/setup-upload-image"

                val isSetupBinaryUpload =
                    isSetupZipUpload || isSetupImageUpload

                if (isSetupZipUpload && contentLength > MAX_SETUP_UPLOAD_BYTES) {
                    writeText(
                        it.getOutputStream(),
                        413,
                        "{" + "\"ok\":false,\"error\":\"zip file too large\"}",
                        "application/json; charset=utf-8"
                    )
                    return
                }

                if (isSetupImageUpload && contentLength > MAX_SETUP_IMAGE_UPLOAD_BYTES) {
                    writeText(
                        it.getOutputStream(),
                        413,
                        "{" + "\"ok\":false,\"error\":\"image file too large\"}",
                        "application/json; charset=utf-8"
                    )
                    return
                }

                val bodyBytes =
                    if (isSetupBinaryUpload && contentLength > 0) {
                        readRequestBody(input, contentLength)
                    } else {
                        ByteArray(0)
                    }


                when {


                    /*
                     * Main admin page.
                     */
                    method == "GET" &&
                            target == "/" -> {

                        writeText(
                            it.getOutputStream(),
                            200,
                            AdminPage.HTML,
                            "text/html; charset=utf-8"
                        )
                    }


                    /*
                     * MJPEG camera stream.
                     */
                    method == "GET" &&
                            target == "/stream" -> {

                        camera.writeMjpegStream(
                            it.getOutputStream()
                        )
                    }


                    /*
                     * Status API.
                     */
                    method == "GET" &&
                            target == "/api/status" -> {

                        val moving =
                            abs(lastX) > 0.001f ||
                                    abs(lastY) > 0.001f


                        val json =
                            """
                            {
                                "camera":${camera.isRunning},
                                "ip":"${localIp()}",
                                "port":$PORT,
                                "moving":$moving
                            }
                            """.trimIndent()


                        writeText(
                            it.getOutputStream(),
                            200,
                            json,
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Physical shelf ranges configured by the library staff.
                     */
                    method == "GET" &&
                            target == "/api/shelf-ranges" -> {

                        val ranges = shelfRangeDatabase.list()
                        val json = ranges.joinToString(",") { range ->
                            "{" +
                                    "\"id\":\"${jsonEscape(range.id)}\"," +
                                    "\"text\":\"${jsonEscape(range.text)}\"," +
                                    "\"hasLocation\":${range.hasLocation}" +
                                    "}"
                        }

                        writeText(
                            it.getOutputStream(),
                            200,
                            "{\"ranges\":[$json]}",
                            "application/json; charset=utf-8"
                        )
                    }


                    method == "POST" &&
                            target == "/api/shelf-ranges/location" -> {

                        val text = query["text"]?.trim().orEmpty()
                        val id = query["id"]?.trim().orEmpty().ifBlank { null }
                        val main = context as? MainActivity

                        if (main == null || text.isBlank()) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{\"ok\":false,\"error\":\"range text required\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        try {
                            val position = robot.getPosition()
                            val saved = shelfRangeDatabase.upsert(
                                text = text,
                                x = position.x.toDouble(),
                                y = position.y.toDouble(),
                                yaw = position.yaw.toDouble(),
                                id = id
                            )

                            writeText(
                                it.getOutputStream(),
                                200,
                                "{\"ok\":true,\"id\":\"${jsonEscape(saved.id)}\",\"text\":\"${jsonEscape(saved.text)}\",\"x\":${position.x},\"y\":${position.y},\"yaw\":${position.yaw}}",
                                "application/json; charset=utf-8"
                            )
                        } catch (e: IllegalArgumentException) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{\"ok\":false,\"error\":\"${jsonEscape(e.message ?: "invalid range")}\"}",
                                "application/json; charset=utf-8"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not save shelf range", e)
                            writeText(
                                it.getOutputStream(),
                                500,
                                "{\"ok\":false,\"error\":\"could not save shelf range\"}",
                                "application/json; charset=utf-8"
                            )
                        }
                    }


                    method == "POST" &&
                            target == "/api/shelf-ranges/delete" -> {

                        val id = query["id"]?.trim().orEmpty()
                        val removed = id.isNotBlank() && shelfRangeDatabase.delete(id)
                        writeText(
                            it.getOutputStream(),
                            200,
                            "{\"ok\":$removed}",
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Book request usage data.
                     */
                    method == "GET" &&
                            target == "/api/usage" -> {

                        val usage =
                            usageRepository.snapshot()

                        val errors =
                            usage.errors.joinToString(",") { error ->
                                "{\"message\":\"${jsonEscape(error.message)}\",\"count\":${error.count}}"
                            }

                        val json =
                            "{" +
                                    "\"requestsToday\":${usage.requestsToday}," +
                                    "\"requestsAllTime\":${usage.requestsAllTime}," +
                                    "\"failedRequests\":${usage.failedRequests}," +
                                    "\"errors\":[$errors]" +
                                    "}"

                        writeText(
                            it.getOutputStream(),
                            200,
                            json,
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Trigger a manual update check.
                     */
                    method == "POST" &&
                            target == "/api/update-check" -> {

                        (context as? MainActivity)
                            ?.triggerUpdateCheck()


                        writeText(
                            it.getOutputStream(),
                            200,
                            """
                            {
                                "ok":true
                            }
                            """.trimIndent(),
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Open setup mode after a PIN check.
                     */
                    method == "POST" &&
                           target == "/api/setup-mode" -> {

                       val pin = query["pin"]?.trim().orEmpty()
                       val main = context as? MainActivity

                       if (main == null || !main.openSetupModeIfAllowed(pin)) {
                           writeText(
                               it.getOutputStream(),
                               403,
                               """
                               {
                                   "ok":false,
                                   "error":"invalid pin"
                               }
                               """.trimIndent(),
                               "application/json; charset=utf-8"
                           )
                           return
                       }

                       writeText(
                           it.getOutputStream(),
                           200,
                           """
                           {
                               "ok":true
                           }
                           """.trimIndent(),
                           "application/json; charset=utf-8"
                       )
                    }

                    /*
                     * Upload a setup ZIP from admin panel and import images for setup mode.
                     */
                    method == "POST" &&
                            target == "/api/setup-upload-zip" -> {

                        val pin = query["pin"]?.trim().orEmpty()
                        val main = context as? MainActivity

                        if (main == null || !main.isSetupPinValid(pin)) {
                            writeText(
                                it.getOutputStream(),
                                403,
                                "{" + "\"ok\":false,\"error\":\"invalid pin\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        if (bodyBytes.isEmpty()) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"request body is empty\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val requestedName =
                            query["filename"]
                                ?.trim()
                                .orEmpty()

                        val safeFileName =
                            sanitizeUploadFileName(
                                if (requestedName.isBlank()) "kuvat.zip" else requestedName
                            )

                        if (!safeFileName.lowercase().endsWith(".zip")) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"filename must end with .zip\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val uploadDir = File(context.filesDir, "setup_uploads")
                        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
                            writeText(
                                it.getOutputStream(),
                                500,
                                "{" + "\"ok\":false,\"error\":\"could not create upload directory\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val zipFile = File(uploadDir, safeFileName)
                        zipFile.writeBytes(bodyBytes)

                        val imported =
                            ShelfRepository(context)
                                .importZip(zipFile.absolutePath)

                        writeText(
                            it.getOutputStream(),
                            200,
                            "{" +
                                    "\"ok\":true," +
                                    "\"importedCount\":${imported.size}" +
                                    "}",
                            "application/json; charset=utf-8"
                        )
                    }

                    /*
                     * Upload a single setup photo image from admin panel.
                     */
                    method == "POST" &&
                            target == "/api/setup-upload-image" -> {

                        val pin = query["pin"]?.trim().orEmpty()
                        val main = context as? MainActivity

                        if (main == null || !main.isSetupPinValid(pin)) {
                            writeText(
                                it.getOutputStream(),
                                403,
                                "{" + "\"ok\":false,\"error\":\"invalid pin\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        if (bodyBytes.isEmpty()) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"request body is empty\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val requestedName =
                            query["filename"]
                                ?.trim()
                                .orEmpty()

                        val safeFileName =
                            sanitizeUploadFileName(
                                if (requestedName.isBlank()) {
                                    "setup_${System.currentTimeMillis()}.jpg"
                                } else {
                                    requestedName
                                }
                            )

                        val lowerName = safeFileName.lowercase()
                        val allowed = lowerName.endsWith(".jpg")
                                || lowerName.endsWith(".jpeg")
                                || lowerName.endsWith(".png")
                                || lowerName.endsWith(".webp")
                        if (!allowed) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"unsupported image extension\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val imagesDir = File(context.filesDir, "shelf_images")
                        if (!imagesDir.exists() && !imagesDir.mkdirs()) {
                            writeText(
                                it.getOutputStream(),
                                500,
                                "{" + "\"ok\":false,\"error\":\"could not create image directory\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val imageFile = File(imagesDir, safeFileName)
                        imageFile.writeBytes(bodyBytes)

                        writeText(
                            it.getOutputStream(),
                            200,
                            "{" +
                                    "\"ok\":true," +
                                    "\"file\":\"${jsonEscape(imageFile.name)}\"" +
                                    "}",
                            "application/json; charset=utf-8"
                        )
                    }

                    /*
                     * Change the stored setup PIN. Requires the current PIN to be provided.
                     */
                    method == "POST" && target == "/api/set-setup-pin" -> {

                        val current = query["current"]?.trim().orEmpty()
                        val newPin = query["new"]?.trim().orEmpty()
                        val main = context as? MainActivity

                        if (main == null) {
                            writeText(
                                it.getOutputStream(),
                                500,
                                "{" + "\"ok\":false,\"error\":\"server\"}" ,
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        if (newPin.isBlank()) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"new pin required\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val ok = try { main.setSetupPin(current, newPin) } catch (e: Exception) { false }

                        if (!ok) {
                            writeText(
                                it.getOutputStream(),
                                403,
                                "{" + "\"ok\":false,\"error\":\"invalid current pin\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        writeText(
                            it.getOutputStream(),
                            200,
                            "{" + "\"ok\":true}",
                            "application/json; charset=utf-8"
                        )
                    }

                    /*
                     * Get manual drive keybinds for keyboard control.
                     */
                    method == "GET" &&
                            target == "/api/control-keybinds" -> {

                        val keybinds = getControlKeybinds()
                        val json =
                            "{" +
                                    "\"forward\":\"${jsonEscape(keybinds["forward"] ?: "w")}\"," +
                                    "\"left\":\"${jsonEscape(keybinds["left"] ?: "a")}\"," +
                                    "\"backward\":\"${jsonEscape(keybinds["backward"] ?: "s")}\"," +
                                    "\"right\":\"${jsonEscape(keybinds["right"] ?: "d")}\"" +
                                    "}"

                        writeText(
                            it.getOutputStream(),
                            200,
                            json,
                            "application/json; charset=utf-8"
                        )
                    }

                    /*
                     * Update manual drive keybinds. Requires setup PIN.
                     */
                    method == "POST" &&
                            target == "/api/control-keybinds" -> {

                        val pin = query["pin"]?.trim().orEmpty()
                        val main = context as? MainActivity
                        if (main == null || !main.isSetupPinValid(pin)) {
                            writeText(
                                it.getOutputStream(),
                                403,
                                "{" + "\"ok\":false,\"error\":\"invalid pin\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        val normalized = normalizeControlKeybinds(
                            forward = query["forward"],
                            left = query["left"],
                            backward = query["backward"],
                            right = query["right"]
                        )

                        if (normalized == null) {
                            writeText(
                                it.getOutputStream(),
                                400,
                                "{" + "\"ok\":false,\"error\":\"invalid keybinds; use unique single letters or digits\"}",
                                "application/json; charset=utf-8"
                            )
                            return
                        }

                        saveControlKeybinds(normalized)

                        writeText(
                            it.getOutputStream(),
                            200,
                            "{" + "\"ok\":true}",
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Get library configuration.
                     */
                    method == "GET" &&
                            target ==
                            "/api/library-config" -> {

                        val json =
                            "{" +
                                    "\"websiteUrl\":\"" +
                                    jsonEscape(
                                        libraryConfig.websiteUrl
                                    ) +
                                    "\"," +

                                    "\"alwaysFilter\":\"" +
                                    jsonEscape(
                                        libraryConfig.alwaysFilter
                                    ) +
                                    "\"," +

                                    "\"libraryBranchName\":\"" +
                                    jsonEscape(
                                        libraryConfig.libraryBranchName
                                    ) +
                                    "\"" +
                                    "}"


                        writeText(
                            it.getOutputStream(),
                            200,
                            json,
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Save library configuration.
                     */
                    method == "POST" &&
                            target ==
                            "/api/library-config" -> {

                        val websiteUrl =
                            query["websiteUrl"]
                                ?.trim()
                                .orEmpty()


                        val alwaysFilter =
                            query["alwaysFilter"]
                                ?.trim()
                                .orEmpty()


                        val libraryBranchName =
                            query["libraryBranchName"]
                                ?.trim()
                                .orEmpty()


                        if (
                            websiteUrl.isBlank()
                        ) {

                            writeText(
                                it.getOutputStream(),
                                400,
                                """
                                {
                                    "ok":false,
                                    "error":"websiteUrl is required"
                                }
                                """.trimIndent(),
                                "application/json; charset=utf-8"
                            )

                        } else if (
                            libraryBranchName.isBlank()
                        ) {

                            writeText(
                                it.getOutputStream(),
                                400,
                                """
                                {
                                    "ok":false,
                                    "error":"libraryBranchName is required"
                                }
                                """.trimIndent(),
                                "application/json; charset=utf-8"
                            )

                        } else {

                            libraryConfig.update(
                                websiteUrl =
                                    websiteUrl,

                                alwaysFilter =
                                    alwaysFilter,

                                libraryBranchName =
                                    libraryBranchName
                            )


                            writeText(
                                it.getOutputStream(),
                                200,
                                """{"ok":true}""",
                                "application/json; charset=utf-8"
                            )
                        }
                    }


                    /*
                     * Movement API.
                     *
                     * x:
                     * +1 = forward
                     * -1 = backward
                     *
                     * y:
                     * +1 = right
                     * -1 = left
                     */
                    method == "POST" &&
                            target ==
                            "/api/move" -> {

                        val x =
                            query["x"]
                                ?.toFloatOrNull()
                                ?.coerceIn(
                                    -1f,
                                    1f
                                )
                                ?: 0f


                        val y =
                            query["y"]
                                ?.toFloatOrNull()
                                ?.coerceIn(
                                    -1f,
                                    1f
                                )
                                ?: 0f


                        lastX =
                            x


                        lastY =
                            y


                        lastCommandAt =
                            System.currentTimeMillis()


                        if (
                            abs(x) < 0.001f &&
                            abs(y) < 0.001f
                        ) {

                            stopRobot()

                        } else {

                            try {

                                robot.skidJoy(
                                    x,
                                    y,
                                    false
                                )

                            } catch (
                                e: Exception
                            ) {

                                Log.e(
                                    TAG,
                                    "skidJoy failed",
                                    e
                                )
                            }
                        }


                        writeText(
                            it.getOutputStream(),
                            200,
                            """{"ok":true}""",
                            "application/json; charset=utf-8"
                        )
                    }


                    /*
                     * Stop API.
                     */
                    method == "POST" &&
                            target ==
                            "/api/stop" -> {

                        stopRobot()


                        writeText(
                            it.getOutputStream(),
                            200,
                            """{"ok":true}""",
                            "application/json; charset=utf-8"
                        )
                    }


                    else -> {

                        writeText(
                            it.getOutputStream(),
                            404,
                            "Not found"
                        )
                    }
                }

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Request handling failed",
                    e
                )
            }
        }
    }


    private fun stopRobot() {

        lastX =
            0f


        lastY =
            0f


        lastCommandAt =
            System.currentTimeMillis()


        try {

            robot.stopMovement()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "stopMovement failed",
                e
            )
        }
    }


    private fun jsonEscape(
        value: String
    ): String {

        return value
            .replace(
                "\\",
                "\\\\"
            )
            .replace(
                "\"",
                "\\\""
            )
            .replace(
                "\n",
                "\\n"
            )
            .replace(
                "\r",
                "\\r"
            )
    }


    private fun parseQuery(
        query: String
    ): Map<String, String> {

        return query
            .split('&')
            .filter {

                it.isNotBlank()
            }
            .mapNotNull {

                val p =
                    it.split(
                        '=',
                        limit = 2
                    )


                if (
                    p.size == 2
                ) {

                    URLDecoder.decode(
                        p[0],
                        "UTF-8"
                    ) to
                            URLDecoder.decode(
                                p[1],
                                "UTF-8"
                            )

                } else {

                    null
                }

            }
            .toMap()
    }


    private fun readAsciiLine(
        input: BufferedInputStream
    ): String? {
        val bytes = mutableListOf<Byte>()

        while (true) {
            val raw = input.read()
            if (raw == -1) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            }

            val b = raw.toByte()
            if (b == '\n'.code.toByte()) {
                break
            }

            if (b != '\r'.code.toByte()) {
                bytes.add(b)
            }
        }

        return bytes.toByteArray()
            .toString(StandardCharsets.US_ASCII)
    }


    private fun readRequestBody(
        input: BufferedInputStream,
        contentLength: Int
    ): ByteArray {
        if (contentLength < 0) {
            throw IOException("Invalid content-length")
        }

        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read < 0) {
                throw IOException("Unexpected end of request body")
            }
            offset += read
        }
        return body
    }


    private fun sanitizeUploadFileName(
        original: String
    ): String {
        val stripped =
            original
                .replace("\\", "/")
                .substringAfterLast('/')
                .trim()

        val cleaned =
            stripped
                .replace(Regex("[^A-Za-z0-9._-]"), "_")

        return cleaned.ifBlank { "kuvat.zip" }
    }


    private fun getControlKeybinds(): Map<String, String> {
        val forward = controlKeybindPrefs.getString("forward", "w") ?: "w"
        val left = controlKeybindPrefs.getString("left", "a") ?: "a"
        val backward = controlKeybindPrefs.getString("backward", "s") ?: "s"
        val right = controlKeybindPrefs.getString("right", "d") ?: "d"
        return mapOf(
            "forward" to forward,
            "left" to left,
            "backward" to backward,
            "right" to right
        )
    }

    private fun normalizeControlKeybinds(
        forward: String?,
        left: String?,
        backward: String?,
        right: String?
    ): Map<String, String>? {
        val f = normalizeKeybindToken(forward) ?: return null
        val l = normalizeKeybindToken(left) ?: return null
        val b = normalizeKeybindToken(backward) ?: return null
        val r = normalizeKeybindToken(right) ?: return null

        val unique = setOf(f, l, b, r)
        if (unique.size < 4) return null

        return mapOf(
            "forward" to f,
            "left" to l,
            "backward" to b,
            "right" to r
        )
    }

    private fun normalizeKeybindToken(value: String?): String? {
        val v = value?.trim()?.lowercase().orEmpty()
        if (v.length != 1) return null
        val c = v[0]
        if (!c.isLetterOrDigit()) return null
        return v
    }

    private fun saveControlKeybinds(keybinds: Map<String, String>) {
        controlKeybindPrefs.edit()
            .putString("forward", keybinds["forward"])
            .putString("left", keybinds["left"])
            .putString("backward", keybinds["backward"])
            .putString("right", keybinds["right"])
            .apply()
    }


    private fun writeText(
        out: OutputStream,
        status: Int,
        body: String,
        contentType: String =
            "text/plain; charset=utf-8"
    ) {

        val bytes =
            body.toByteArray(
                StandardCharsets.UTF_8
            )


        val reason =
            when (
                status
            ) {

                200 ->
                    "OK"

                400 ->
                    "Bad Request"

                403 ->
                    "Forbidden"

                413 ->
                    "Payload Too Large"

                404 ->
                    "Not Found"

                429 ->
                    "Too Many Requests"

                else ->
                    "Error"
            }


        val header =
            "HTTP/1.1 $status $reason\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"


        out.write(
            header.toByteArray(
                StandardCharsets.US_ASCII
            )
        )


        out.write(
            bytes
        )


        out.flush()
    }


    private fun localIp(): String {

        return try {

            Collections
                .list(
                    NetworkInterface
                        .getNetworkInterfaces()
                )
                .flatMap {

                    Collections.list(
                        it.inetAddresses
                    )
                }
                .firstOrNull {

                    !it.isLoopbackAddress &&
                            it is java.net.Inet4Address
                }
                ?.hostAddress
                ?: "unknown"

        } catch (
            _: Exception
        ) {

            "unknown"
        }
    }
}


private object AdminPage {

    val HTML =
        """
<!doctype html>

<html>

<head>

<meta
    name="viewport"
    content="width=device-width,initial-scale=1">

<title>temi Admin</title>


<style>

html,
body{

    margin:0;

    background:#0b0f14;

    color:#eef2f7;

    font-family:
        system-ui,
        Segoe UI,
        sans-serif;

    height:100%;

    overflow:hidden
}


main{

    display:grid;

    grid-template-columns:
        minmax(0,1fr)
        360px;

    gap:16px;

    height:100%;

    padding:16px;

    box-sizing:border-box
}


.card{

    background:#141b23;

    border:
        1px solid #283442;

    border-radius:16px;

    box-shadow:
        0 8px 30px #0005;

    overflow:hidden
}


h1{

    font-size:20px;

    margin:0
}


h2{

    font-size:17px;

    margin:0
}


.top{

    display:flex;

    justify-content:
        space-between;

    align-items:center;

    padding:
        14px
        16px;

    border-bottom:
        1px solid #283442
}


#feed{

    width:100%;

    height:
        calc(100vh - 70px);

    object-fit:contain;

    background:#050709;

    display:block
}


.controls{

    padding:18px;

    overflow-y:auto
}


.hint{

    color:#9aa7b5;

    font-size:13px;

    line-height:1.5
}


.section{

    margin-top:20px;

    padding-top:16px;

    border-top:
        1px solid #283442
}


#pad{

    height:310px;

    position:relative;

    margin-top:18px;

    display:grid;

    grid-template-columns:
        1fr
        1fr
        1fr;

    grid-template-rows:
        1fr
        1fr
        1fr;

    gap:10px
}


button{

    border:
        1px solid #3a4858;

    background:#202b37;

    color:white;

    border-radius:14px;

    font-size:26px;

    font-weight:700;

    touch-action:none;

    user-select:none;

    -webkit-user-select:none;

    cursor:pointer
}


button:active,
.down{

    background:#345a78;

    transform:scale(.98)
}


.w{

    grid-column:2;

    grid-row:1
}


.a{

    grid-column:1;

    grid-row:2
}


.s{

    grid-column:2;

    grid-row:2
}


.d{

    grid-column:3;

    grid-row:2
}


.stop{

    grid-column:3;

    grid-row:3;

    font-size:16px;

    background:#552b31
}


#status{

    font-size:12px;

    color:#9aa7b5;

    margin-top:14px;

    line-height:1.5
}


#controllerStatus{

    font-size:12px;

    margin-top:10px;

    padding:10px;

    border-radius:10px;

    background:#0e141b;

    border:
        1px solid #283442;

    color:#9aa7b5;

    line-height:1.4
}


.controller-connected{

    color:#9fe3ad !important
}


.controller-controls{

    display:grid;

    grid-template-columns:
        1fr
        1fr;

    gap:8px;

    margin-top:10px
}


.controller-key{

    background:#0e141b;

    border:
        1px solid #283442;

    border-radius:10px;

    padding:9px;

    text-align:center;

    font-size:12px;

    color:#c7d0da
}


.config-label{

    display:block;

    margin-top:14px;

    margin-bottom:6px;

    font-size:13px;

    color:#c7d0da
}


.config-input{

    width:100%;

    box-sizing:border-box;

    padding:10px;

    border-radius:10px;

    border:
        1px solid #3a4858;

    background:#0e141b;

    color:#eef2f7;

    font-size:14px
}


.config-save{

    width:100%;

    margin-top:16px;

    padding:12px;

    font-size:15px;

    background:#345a78
}


.tabs{

    display:grid;

    grid-template-columns:1fr 1fr;

    gap:8px;

    margin-bottom:18px
}


.tab{

    padding:10px;

    font-size:14px
}


.tab-active{

    background:#345a78
}


.panel-hidden{

    display:none
}


.usage-grid{

    display:grid;

    grid-template-columns:1fr 1fr;

    gap:10px;

    margin-top:14px
}


.usage-stat{

    padding:12px;

    border:1px solid #283442;

    border-radius:10px;

    background:#0e141b
}


.usage-stat strong{

    display:block;

    font-size:24px
}


.usage-stat span{

    color:#9aa7b5;

    font-size:12px
}


#usageErrors{

    margin-top:14px;

    color:#c7d0da;

    font-size:13px;

    line-height:1.5
}


#updateStatus{

    margin-top:10px;

    font-size:13px;

    color:#9aa7b5
}


#libraryConfigStatus{

    margin-top:10px;

    font-size:13px;

    color:#9aa7b5
}


@media(max-width:850px){

    main{

        grid-template-columns:
            1fr;

        overflow:auto
    }


    .card:first-child{

        height:60vh
    }


    #feed{

        height:
            calc(60vh - 55px)
    }


    body{

        overflow:auto
    }
}

</style>

</head>


<body>


<main>


<section class="card">


<div class="top">

<h1>
    temi camera
</h1>

<span id="cam">
    connecting…
</span>

</div>


<img
    id="feed"
    src="/stream"
    alt="temi camera">


</section>


<section class="card controls">


<div class="tabs">

<button
    class="tab tab-active"
    id="driveTab">

    Manual drive

</button>


<button
    class="tab"
    id="usageTab">

    Usage data

</button>


<button
    class="tab"
    id="shelfRangesTab">

    Shelf ranges

</button>

</div>


<div id="drivePanel">


<h1>
    Manual drive
</h1>


<p class="hint">

    Use the buttons, keyboard,
    or a game controller to
    drive the robot.

    Release the key/button
    to stop.

</p>


<div id="pad">


<button
    id="driveForwardButton"
    class="w"
    data-action="forward">
 
    Forward
 
</button>


<button
    id="driveLeftButton"
    class="a"
    data-action="left">
 
    Left
 
</button>


<button
    id="driveBackwardButton"
    class="s"
    data-action="backward">
 
    Backward
 
</button>


<button
    id="driveRightButton"
    class="d"
    data-action="right">
 
    Right
 
</button>


<button
    class="stop"
    id="stop">

    STOP

</button>


</div>


<div class="section">


<h2>
    Controller
</h2>


<p class="hint">

    Connect a game controller
    to the device running
    this page.

</p>


<div id="controllerStatus">

    No controller connected

</div>


<div class="controller-controls">


<div class="controller-key">

    Left stick ↑<br>

    <b>Forward</b>

</div>


<div class="controller-key">

    Left stick ↓<br>

    <b>Backward</b>

</div>


<div class="controller-key">

    Left stick ←<br>

    <b>Turn left</b>

</div>


<div class="controller-key">

    Left stick →<br>

    <b>Turn right</b>

</div>


</div>


</div>


<div class="section">

<h2>
    Drive keyboard keybinds
</h2>

<p class="hint">
    Set unique one-character keys for manual drive.
</p>

<label class="config-label" for="keybindForward">Forward</label>
<input id="keybindForward" class="config-input" type="text" maxlength="1" autocomplete="off">

<label class="config-label" for="keybindLeft">Left</label>
<input id="keybindLeft" class="config-input" type="text" maxlength="1" autocomplete="off">

<label class="config-label" for="keybindBackward">Backward</label>
<input id="keybindBackward" class="config-input" type="text" maxlength="1" autocomplete="off">

<label class="config-label" for="keybindRight">Right</label>
<input id="keybindRight" class="config-input" type="text" maxlength="1" autocomplete="off">

<button id="saveControlKeybinds" class="config-save" style="margin-top:10px;background:#3a4668">
    Save keybinds
</button>

<div id="keybindStatus" class="hint"></div>

</div>


<div class="section">


<h2>
    Library configuration
</h2>


<p class="hint">

    These settings belong to
    this individual robot.

    They are stored locally and
    survive normal application
    updates.

</p>


<label
    class="config-label"
    for="libraryWebsiteUrl">

    Library / Finna page URL

</label>


<input
    id="libraryWebsiteUrl"
    class="config-input"
    type="text"
    autocomplete="off">


<label
    class="config-label"
    for="libraryAlwaysFilter">

    Finna building filter

</label>


<input
    id="libraryAlwaysFilter"
    class="config-input"
    type="text"
    autocomplete="off">


<label
    class="config-label"
    for="libraryBranchName">

    Library branch name

</label>


<input
    id="libraryBranchName"
    class="config-input"
    type="text"
    autocomplete="off">


<button
    id="saveLibraryConfig"
    class="config-save">

    Save library configuration

</button>


<button
    id="checkUpdates"
    class="config-save">
 
    Check for updates now
 
</button>
 
 
<button
    id="setupModeButton"
    class="config-save"
    style="margin-top:10px;background:#2d4d66">
 
    Open setup mode
 
</button>

<button
    id="changePinButton"
    class="config-save"
    style="margin-top:10px;background:#66342d">
 
    Change setup PIN
 
</button>

<label
    class="config-label"
    for="setupZipFile"
    style="margin-top:12px">
 
    Setup photos ZIP (.zip)
 
</label>

<input
    id="setupZipFile"
    class="config-input"
    type="file"
    accept=".zip,application/zip,application/x-zip-compressed">

<button
    id="uploadSetupZip"
    class="config-save"
    style="margin-top:10px;background:#2f6b3f">
 
    Upload setup photos ZIP
 
</button>

<label
    class="config-label"
    for="setupPhotoFiles"
    style="margin-top:12px">
 
    Setup photos (JPG/PNG/WEBP, multiple)
 
</label>

<input
    id="setupPhotoFiles"
    class="config-input"
    type="file"
    accept="image/jpeg,image/png,image/webp"
    multiple>

<button
    id="uploadSetupPhotos"
    class="config-save"
    style="margin-top:10px;background:#355f9f">
 
    Upload setup photos
 
</button>
  
  
<div
    id="libraryConfigStatus">

</div>


<div
    id="updateStatus">

</div>


</div>


</div>


<div id="shelfRangesPanel" class="panel-hidden">

<h1>
    Shelf ranges
</h1>

<p class="hint">
    Enter the physical alphabetical ranges used by the library. Example: AIK84.2CON-D.
    The location button stores temi's current coordinates for that exact range.
</p>

<div class="section">

<div style="display:flex;gap:10px;align-items:center;flex-wrap:wrap">
    <button id="addShelfRange" class="config-save">+ Add shelf range</button>
    <span id="shelfRangesStatus" class="hint"></span>
</div>

<div id="shelfRangesList" style="margin-top:14px"></div>

</div>

</div>


<div id="usagePanel" class="panel-hidden">


<h1>
    Usage data
</h1>


<p class="hint">
    Book shelf requests stored on this robot.
</p>


<div class="usage-grid">

<div class="usage-stat">
    <strong id="requestsToday">0</strong>
    <span>Requests today</span>
</div>


<div class="usage-stat">
    <strong id="requestsAllTime">0</strong>
    <span>Requests all time</span>
</div>


<div class="usage-stat">
    <strong id="failedRequests">0</strong>
    <span>Failed requests</span>
</div>

</div>


<div class="section">

<h2>Errors</h2>

<div id="usageErrors">No errors recorded.</div>

</div>


<div id="usageStatus" class="hint"></div>

</div>


<div id="status">

    LAN admin panel · port 8080

</div>


</section>


</main>


<script>


const activeActions =
    new Set();


let timer =
    0;


let gamepad =
    null;


let controllerActive =
    false;


const DEADZONE =
    0.15;

let keybinds = {
    forward: 'w',
    left: 'a',
    backward: 's',
    right: 'd'
};


/*
 * Returns true when the user is currently
 * typing into an editable field.
 */
function isTypingTarget(
    target
){

    if(
        !target
    ){

        return false;
    }


    const tag =
        target.tagName
            ? target.tagName.toLowerCase()
            : '';


    if(
        tag === 'input' ||
        tag === 'textarea' ||
        tag === 'select'
    ){

        return true;
    }


    if(
        target.isContentEditable
    ){

        return true;
    }


    return false;
}


function applyDeadzone(
    value
){

    if(
        Math.abs(value) <
        DEADZONE
    ){

        return 0;
    }


    const sign =
        value < 0
            ? -1
            : 1;


    return sign *
        (
            (
                Math.abs(value) -
                DEADZONE
            ) /
            (
                1 - DEADZONE
            )
        );
}


function send(){

    const x =
        (activeActions.has('forward') ? 1 : 0) +
        (activeActions.has('backward') ? -1 : 0);


    const y =
        (activeActions.has('left') ? 1 : 0) +
        (activeActions.has('right') ? -1 : 0);


    fetch(
        '/api/move?x=' +
        x +
        '&y=' +
        y,
        {
            method:'POST'
        }
    ).catch(()=>{});
}


function start(
    action,
    button
){

    if(
        !button
    ){

        return;
    }


    activeActions.add(
        action
    );


    button.classList.add(
        'down'
    );


    send();


    clearInterval(
        timer
    );


    timer =
        setInterval(
            send,
            150
        );
}


function end(
    action,
    button
){

    activeActions.delete(
        action
    );


    if(
        button
    ){

        button.classList.remove(
            'down'
        );
    }


    send();


    if(
        !activeActions.size
    ){

        clearInterval(
            timer
        );


        timer =
            0;
    }
}


document
    .querySelectorAll(
        'button[data-action]'
    )
    .forEach(
        button => {

            const action =
                button.dataset.action;


            button.onpointerdown =
                event => {

                    event.preventDefault();

                    start(
                        action,
                        button
                    );
                };


            button.onpointerup =
                event => {

                    event.preventDefault();

                    end(
                        action,
                        button
                    );
                };


            button.onpointercancel =
                event => {

                    event.preventDefault();

                    if(
                        activeActions.has(
                            action
                        )
                    ){

                        end(
                            action,
                            button
                        );
                    }
                };


            button.onpointerleave =
                () => {

                    if(
                        activeActions.has(
                            action
                        )
                    ){

                        end(
                            action,
                            button
                        );
                    }
                };
        }
    );


/*
 * Keyboard movement.
 *
 * Keyboard keys work normally inside text fields and
 * do not control the robot there.
 */
function actionForKey(
    key
){
    for (const [action, mapped] of Object.entries(keybinds)) {
        if (mapped === key) {
            return action;
        }
    }
    return null;
}

function buttonForAction(
    action
){
    return document.querySelector(
        'button[data-action="' +
        action +
        '"]'
    );
}

addEventListener(
    'keydown',
    event => {

        if(
            isTypingTarget(
                event.target
            )
        ){

            return;
        }


        const key =
            event.key.toLowerCase();


        const action = actionForKey(key);
        if(!action){

            return;
        }


        event.preventDefault();


        if(
            activeActions.has(
                action
            )
        ){

            return;
        }


        const button =
            buttonForAction(action);


        start(
            action,
            button
        );
    }
);


addEventListener(
    'keyup',
    event => {

        const key =
            event.key.toLowerCase();


        const action = actionForKey(key);
        if(!action){

            return;
        }


        if(
            !activeActions.has(
                action
            )
        ){

            return;
        }


        event.preventDefault();


        const button =
            buttonForAction(action);


        end(
            action,
            button
        );
    }
);


window.addEventListener(
    'blur',
    () => {

        if(
            keys.size
        ){

            keys.clear();


            clearInterval(
                timer
            );


            timer =
                0;


            fetch(
                '/api/stop',
                {
                    method:'POST'
                }
            ).catch(()=>{});


            document
                .querySelectorAll(
                    '.down'
                )
                .forEach(
                    element => {

                        element.classList.remove(
                            'down'
                        );
                    }
                );
        }
    }
);


document
    .getElementById(
        'stop'
    )
    .onclick =
        () => {

            keys.clear();


            clearInterval(
                timer
            );


            timer =
                0;


            controllerActive =
                false;


            fetch(
                '/api/stop',
                {
                    method:'POST'
                }
            ).catch(()=>{});


            document
                .querySelectorAll(
                    '.down'
                )
                .forEach(
                    element => {

                        element.classList.remove(
                            'down'
                        );
                    }
                );
        };


function sendController(){

    if(
        !gamepad
    ){

        return;
    }


    let forward =
        -applyDeadzone(
            gamepad.axes[1] || 0
        );


    let turn =
        -applyDeadzone(
            gamepad.axes[0] || 0
        );


    if(
        gamepad.buttons[12] &&
        gamepad.buttons[12].pressed
    ){

        forward =
            1;
    }


    if(
        gamepad.buttons[13] &&
        gamepad.buttons[13].pressed
    ){

        forward =
            -1;
    }


    if(
        gamepad.buttons[14] &&
        gamepad.buttons[14].pressed
    ){

        turn =
            1;
    }


    if(
        gamepad.buttons[15] &&
        gamepad.buttons[15].pressed
    ){

        turn =
            -1;
    }


    if(
        Math.abs(forward) <
        0.01 &&
        Math.abs(turn) <
        0.01
    ){

        if(
            controllerActive
        ){

            controllerActive =
                false;


            fetch(
                '/api/stop',
                {
                    method:'POST'
                }
            ).catch(()=>{});
        }


        return;
    }


    controllerActive =
        true;


    fetch(
        '/api/move?x=' +
        forward +
        '&y=' +
        turn,
        {
            method:'POST'
        }
    ).catch(()=>{});
}


window.addEventListener(
    'gamepadconnected',
    event => {

        gamepad =
            event.gamepad;


        const status =
            document.getElementById(
                'controllerStatus'
            );


        status.textContent =
            'Controller connected: ' +
            gamepad.id;


        status.classList.add(
            'controller-connected'
        );
    }
);


window.addEventListener(
    'gamepaddisconnected',
    event => {

        if(
            gamepad &&
            event.gamepad.index ===
            gamepad.index
        ){

            gamepad =
                null;


            controllerActive =
                false;


            fetch(
                '/api/stop',
                {
                    method:'POST'
                }
            ).catch(()=>{});


            const status =
                document.getElementById(
                    'controllerStatus'
                );


            status.textContent =
                'No controller connected';


            status.classList.remove(
                'controller-connected'
            );
        }
    }
);


function pollGamepad(){

    const pads =
        navigator.getGamepads
            ? navigator.getGamepads()
            : [];


    if(
        gamepad
    ){

        const updated =
            pads[
                gamepad.index
            ];


        if(
            updated
        ){

            gamepad =
                updated;


            sendController();
        }
    }


    requestAnimationFrame(
        pollGamepad
    );
}


function findExistingGamepad(){

    if(
        !navigator.getGamepads
    ){

        return;
    }


    const pads =
        navigator.getGamepads();


    for(
        let i = 0;
        i < pads.length;
        i++
    ){

        if(
            pads[i]
        ){

            gamepad =
                pads[i];


            const status =
                document.getElementById(
                    'controllerStatus'
                );


            status.textContent =
                'Controller connected: ' +
                gamepad.id;


            status.classList.add(
                'controller-connected'
            );


            break;
        }
    }
}


findExistingGamepad();

pollGamepad();


function showPanel(
    panel,
    tab
){

    document
        .getElementById('drivePanel')
        .classList.toggle(
            'panel-hidden',
            panel !== 'drive'
        );

    document
        .getElementById('usagePanel')
        .classList.toggle(
            'panel-hidden',
            panel !== 'usage'
        );

    document
        .getElementById('shelfRangesPanel')
        .classList.toggle(
            'panel-hidden',
            panel !== 'shelfRanges'
        );

    document
        .querySelectorAll('.tab')
        .forEach(element => element.classList.remove('tab-active'));

    document
        .getElementById(tab)
        .classList.add('tab-active');
}


async function loadUsage(){

    const status =
        document.getElementById('usageStatus');

    try {

        const response =
            await fetch('/api/usage', { cache:'no-store' });

        if (!response.ok) {
            throw new Error('Could not load usage data');
        }

        const usage = await response.json();

        document.getElementById('requestsToday').textContent =
            usage.requestsToday || 0;
        document.getElementById('requestsAllTime').textContent =
            usage.requestsAllTime || 0;
        document.getElementById('failedRequests').textContent =
            usage.failedRequests || 0;

        const errors = document.getElementById('usageErrors');
        errors.replaceChildren();

        if (!usage.errors || !usage.errors.length) {
            errors.textContent = 'No errors recorded.';
        } else {
            usage.errors.forEach(error => {
                const row = document.createElement('div');
                row.textContent = error.count + ' × ' + error.message;
                errors.appendChild(row);
            });
        }

        status.textContent = 'Updated just now.';
    } catch (error) {
        status.textContent = 'Could not load usage data: ' + error.message;
    }
}


document.getElementById('driveTab').addEventListener('click', () => {
    showPanel('drive', 'driveTab');
});


document.getElementById('usageTab').addEventListener('click', () => {
    showPanel('usage', 'usageTab');
    loadUsage();
});


document.getElementById('shelfRangesTab').addEventListener('click', () => {
    showPanel('shelfRanges', 'shelfRangesTab');
    loadShelfRanges();
});


let shelfRanges = [];


function renderShelfRanges(){
    const list = document.getElementById('shelfRangesList');
    list.replaceChildren();

    shelfRanges.forEach((range, index) => {
        const row = document.createElement('div');
        row.style.display = 'flex';
        row.style.gap = '10px';
        row.style.alignItems = 'center';
        row.style.marginBottom = '10px';
        row.style.flexWrap = 'wrap';

        const input = document.createElement('input');
        input.className = 'config-input';
        input.style.flex = '1 1 260px';
        input.type = 'text';
        input.placeholder = 'AIK84.2CON-D';
        input.value = range.text || '';
        input.autocomplete = 'off';

        input.addEventListener('change', () => {
            range.text = input.value;
        });

        const locationButton = document.createElement('button');
        locationButton.className = 'config-save';
        locationButton.textContent = range.hasLocation ? 'Set location again' : 'Set current location';
        locationButton.style.background = '#2f6b3f';
        locationButton.addEventListener('click', async () => {
            const text = input.value.trim();
            if (!text) {
                document.getElementById('shelfRangesStatus').textContent = 'Enter a shelf range first.';
                return;
            }
            locationButton.disabled = true;
            document.getElementById('shelfRangesStatus').textContent = 'Saving current robot position…';
            try {
                const params = new URLSearchParams({ text });
                if (range.id) params.set('id', range.id);
                const response = await fetch('/api/shelf-ranges/location?' + params.toString(), { method: 'POST' });
                const result = await response.json();
                if (!response.ok || !result.ok) throw new Error(result.error || 'Save failed');
                range.id = result.id;
                range.text = result.text;
                range.hasLocation = true;
                locationButton.textContent = 'Set location again';
                document.getElementById('shelfRangesStatus').textContent =
                    'Saved ' + result.text + ' at x=' + Number(result.x).toFixed(2) +
                    ', y=' + Number(result.y).toFixed(2) + ', yaw=' + Number(result.yaw).toFixed(1);
            } catch (error) {
                document.getElementById('shelfRangesStatus').textContent = 'Could not save: ' + error.message;
            } finally {
                locationButton.disabled = false;
            }
        });

        const deleteButton = document.createElement('button');
        deleteButton.className = 'config-save';
        deleteButton.textContent = '×';
        deleteButton.style.background = '#66342d';
        deleteButton.title = 'Delete shelf range';
        deleteButton.addEventListener('click', async () => {
            if (!range.id) {
                shelfRanges.splice(index, 1);
                renderShelfRanges();
                return;
            }
            try {
                const response = await fetch('/api/shelf-ranges/delete?id=' + encodeURIComponent(range.id), { method: 'POST' });
                const result = await response.json();
                if (!response.ok || !result.ok) throw new Error('Delete failed');
                shelfRanges = shelfRanges.filter(item => item.id !== range.id);
                renderShelfRanges();
            } catch (error) {
                document.getElementById('shelfRangesStatus').textContent = 'Could not delete: ' + error.message;
            }
        });

        row.appendChild(input);
        row.appendChild(locationButton);
        row.appendChild(deleteButton);
        list.appendChild(row);
    });
}


function addShelfRangeRow(){
    shelfRanges.push({ id: '', text: '', hasLocation: false });
    renderShelfRanges();
    const inputs = document.querySelectorAll('#shelfRangesList input');
    const last = inputs[inputs.length - 1];
    if (last) last.focus();
}


async function loadShelfRanges(){
    const status = document.getElementById('shelfRangesStatus');
    try {
        const response = await fetch('/api/shelf-ranges', { cache: 'no-store' });
        const result = await response.json();
        if (!response.ok) throw new Error('Could not load shelf ranges');
        shelfRanges = result.ranges || [];
        renderShelfRanges();
        status.textContent = shelfRanges.length + ' shelf range(s) loaded.';
    } catch (error) {
        status.textContent = 'Could not load shelf ranges: ' + error.message;
    }
}


document.getElementById('addShelfRange').addEventListener('click', addShelfRangeRow);


async function loadLibraryConfig(){

    const status =
        document.getElementById(
            'libraryConfigStatus'
        );


    try {

        const response =
            await fetch(
                '/api/library-config',
                {
                    cache:'no-store'
                }
            );


        const config =
            await response.json();


        document
            .getElementById(
                'libraryWebsiteUrl'
            )
            .value =
            config.websiteUrl || '';


        document
            .getElementById(
                'libraryAlwaysFilter'
            )
            .value =
            config.alwaysFilter || '';


        document
            .getElementById(
                'libraryBranchName'
            )
            .value =
            config.libraryBranchName || '';


        status.textContent =
            'Configuration loaded.';

    } catch(
        e
    ) {

        status.textContent =
            'Could not load configuration: ' +
            e.message;
    }
}


document
    .getElementById(
        'saveLibraryConfig'
    )
    .addEventListener(
        'click',
        async () => {

            const status =
                document.getElementById(
                    'libraryConfigStatus'
                );


            status.textContent =
                'Saving...';


            const params =
                new URLSearchParams(
                    {
                        websiteUrl:
                            document
                                .getElementById(
                                    'libraryWebsiteUrl'
                                )
                                .value,

                        alwaysFilter:
                            document
                                .getElementById(
                                    'libraryAlwaysFilter'
                                )
                                .value,

                        libraryBranchName:
                            document
                                .getElementById(
                                    'libraryBranchName'
                                )
                                .value
                    }
                );


            try {

                const response =
                    await fetch(
                        '/api/library-config?' +
                        params.toString(),
                        {
                            method:'POST'
                        }
                    );


                const result =
                    await response.json();


                if(
                    !response.ok ||
                    !result.ok
                ){

                    throw new Error(
                        result.error ||
                        'Save failed'
                    );
                }


                status.textContent =
                    'Saved successfully.';

            } catch(
                e
            ) {

                status.textContent =
                    'Save failed: ' +
                    e.message;
            }
        }
    );


document
    .getElementById(
        'checkUpdates'
    )
    .addEventListener(
        'click',
        async () => {

            const status =
                document.getElementById(
                    'updateStatus'
                );


            status.textContent =
                'Checking for updates...';


            try {

                const response =
                    await fetch(
                        '/api/update-check',
                        {
                            method:'POST'
                        }
                    );


                const result =
                    await response.json();


                if(
                    !response.ok ||
                    !result.ok
                ){

                    throw new Error(
                        result.error ||
                        'Update check failed'
                    );
                }


                status.textContent =
                    'Update check started.';

            } catch(
                e
            ) {

                status.textContent =
                    'Update check failed: ' +
                    e.message;
            }
        }
    );


document
    .getElementById(
        'setupModeButton'
    )
    .addEventListener(
        'click',
        async () => {

            const pin =
                prompt(
                    'Enter setup PIN',
                    ''
                );

            if(
                pin === null
            ){

                return;
            }

            const status =
                document.getElementById(
                    'updateStatus'
                );

            status.textContent =
                'Opening setup mode...';

            try {

                const response =
                    await fetch(
                        '/api/setup-mode?pin=' +
                        encodeURIComponent(pin),
                        {
                            method:'POST'
                        }
                    );

                const result =
                    await response.json();

                if(
                    !response.ok ||
                    !result.ok
                ){

                    throw new Error(
                        result.error ||
                        'Setup mode access denied'
                    );
                }

                status.textContent =
                    'Setup mode opened.';

            } catch(
                e
            ) {

                status.textContent =
                    'Setup mode failed: ' +
                    e.message;
            }
        }
    );


    document.getElementById('changePinButton').addEventListener('click', async () => {
        const current = prompt('Enter current setup PIN', '');
        if (current === null) return;
        const next = prompt('Enter new setup PIN', '');
        if (next === null) return;

        const status = document.getElementById('updateStatus');
        status.textContent = 'Updating PIN...';

        try {
            const response = await fetch('/api/set-setup-pin?current=' + encodeURIComponent(current) + '&new=' + encodeURIComponent(next), { method: 'POST' });
            const result = await response.json();
            if (!response.ok || !result.ok) throw new Error(result.error || 'Change PIN failed');
            status.textContent = 'PIN changed successfully.';
        } catch (e) {
            status.textContent = 'Change PIN failed: ' + e.message;
        }
    });

    document.getElementById('uploadSetupZip').addEventListener('click', async () => {
        const fileInput = document.getElementById('setupZipFile');
        const file = fileInput.files && fileInput.files.length > 0 ? fileInput.files[0] : null;
        const status = document.getElementById('updateStatus');

        if (!file) {
            status.textContent = 'Choose a ZIP file first.';
            return;
        }

        const pin = prompt('Enter setup PIN', '');
        if (pin === null) return;

        status.textContent = 'Uploading setup ZIP...';

        try {
            const response = await fetch(
                '/api/setup-upload-zip?pin=' + encodeURIComponent(pin) + '&filename=' + encodeURIComponent(file.name),
                {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/zip' },
                    body: file
                }
            );

            const result = await response.json();
            if (!response.ok || !result.ok) {
                throw new Error(result.error || 'Upload failed');
            }

            status.textContent = 'Upload complete: ' + result.importedCount + ' images imported. Open setup mode to analyze.';
        } catch (e) {
            status.textContent = 'Setup ZIP upload failed: ' + e.message;
        }
    });

    document.getElementById('uploadSetupPhotos').addEventListener('click', async () => {
        const fileInput = document.getElementById('setupPhotoFiles');
        const files = fileInput.files ? Array.from(fileInput.files) : [];
        const status = document.getElementById('updateStatus');

        if (files.length === 0) {
            status.textContent = 'Choose one or more images first.';
            return;
        }

        const pin = prompt('Enter setup PIN', '');
        if (pin === null) return;

        let uploaded = 0;
        status.textContent = 'Uploading setup photos (0/' + files.length + ')...';

        for (const file of files) {
            try {
                const response = await fetch(
                    '/api/setup-upload-image?pin=' + encodeURIComponent(pin) + '&filename=' + encodeURIComponent(file.name),
                    {
                        method: 'POST',
                        headers: { 'Content-Type': file.type || 'application/octet-stream' },
                        body: file
                    }
                );

                const result = await response.json();
                if (!response.ok || !result.ok) {
                    throw new Error(result.error || 'Upload failed');
                }

                uploaded += 1;
                status.textContent = 'Uploading setup photos (' + uploaded + '/' + files.length + ')...';
            } catch (e) {
                status.textContent = 'Photo upload failed on "' + file.name + '": ' + e.message;
                return;
            }
        }

        status.textContent = 'Photo upload complete: ' + uploaded + ' files uploaded. Open setup mode to analyze.';
    });


async function status(){

    try {

        const response =
            await fetch(
                '/api/status',
                {
                    cache:'no-store'
                }
            );


        const s =
            await response.json();


        document
            .getElementById(
                'cam'
            )
            .textContent =
            s.camera
                ? 'live'
                : 'unavailable';


        document
            .getElementById(
                'status'
            )
            .textContent =
            'LAN: http://' +
            s.ip +
            ':' +
            s.port +
            ' · camera ' +
            (
                s.camera
                    ? 'ready'
                    : 'unavailable'
            );

    } catch(
        e
    ) {

        document
            .getElementById(
                'cam'
            )
            .textContent =
            'connection error';
    }
}


loadLibraryConfig();

status();


setInterval(
    status,
    3000
);

</script>


</body>

</html>
        """.trimIndent()
}