package com.kirjasto.kirjastobotti

import android.content.Context
import android.util.Log

import com.robotemi.sdk.Robot

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
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


    companion object {

        private const val TAG =
            "KirjastobottiAdmin"

        const val PORT =
            8080
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


    @Volatile
    private var lastCommandAt =
        0L


    @Volatile
    private var lastX =
        0f


    @Volatile
    private var lastY =
        0f


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


                val reader =
                    BufferedReader(
                        InputStreamReader(
                            BufferedInputStream(
                                it.getInputStream()
                            ),
                            StandardCharsets.UTF_8
                        )
                    )


                val requestLine =
                    reader.readLine()
                        ?: return


                while (
                    true
                ) {

                    val line =
                        reader.readLine()
                            ?: break


                    if (
                        line.isEmpty()
                    ) {
                        break
                    }
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

                404 ->
                    "Not Found"

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
    class="w"
    data-key="w">

    W

</button>


<button
    class="a"
    data-key="a">

    A

</button>


<button
    class="s"
    data-key="s">

    S

</button>


<button
    class="d"
    data-key="d">

    D

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


<div
    id="libraryConfigStatus">

</div>


<div
    id="updateStatus">

</div>


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


const keys =
    new Set();


let timer =
    0;


let gamepad =
    null;


let controllerActive =
    false;


const DEADZONE =
    0.15;


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
        (keys.has('w') ? 1 : 0) +
        (keys.has('s') ? -1 : 0);


    const y =
        (keys.has('a') ? 1 : 0) +
        (keys.has('d') ? -1 : 0);


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
    key,
    button
){

    if(
        !button
    ){

        return;
    }


    keys.add(
        key
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
    key,
    button
){

    keys.delete(
        key
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
        !keys.size
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
        'button[data-key]'
    )
    .forEach(
        button => {

            const key =
                button.dataset.key;


            button.onpointerdown =
                event => {

                    event.preventDefault();

                    start(
                        key,
                        button
                    );
                };


            button.onpointerup =
                event => {

                    event.preventDefault();

                    end(
                        key,
                        button
                    );
                };


            button.onpointercancel =
                event => {

                    event.preventDefault();

                    if(
                        keys.has(
                            key
                        )
                    ){

                        end(
                            key,
                            button
                        );
                    }
                };


            button.onpointerleave =
                () => {

                    if(
                        keys.has(
                            key
                        )
                    ){

                        end(
                            key,
                            button
                        );
                    }
                };
        }
    );


/*
 * Keyboard movement.
 *
 * W, A, S and D work normally inside
 * text fields and do not control the robot.
 */
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


        if(
            ![
                'w',
                'a',
                's',
                'd'
            ].includes(
                key
            )
        ){

            return;
        }


        event.preventDefault();


        if(
            keys.has(
                key
            )
        ){

            return;
        }


        const button =
            document.querySelector(
                'button[data-key="' +
                key +
                '"]'
            );


        start(
            key,
            button
        );
    }
);


addEventListener(
    'keyup',
    event => {

        const key =
            event.key.toLowerCase();


        if(
            ![
                'w',
                'a',
                's',
                'd'
            ].includes(
                key
            )
        ){

            return;
        }


        if(
            !keys.has(
                key
            )
        ){

            return;
        }


        event.preventDefault();


        const button =
            document.querySelector(
                'button[data-key="' +
                key +
                '"]'
            );


        end(
            key,
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