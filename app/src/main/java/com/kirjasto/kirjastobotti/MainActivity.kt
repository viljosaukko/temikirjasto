package com.kirjasto.kirjastobotti

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

import com.robotemi.sdk.Robot
import com.robotemi.sdk.listeners.OnBeWithMeStatusChangedListener
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.permission.Permission

import kotlin.math.sin
import kotlin.random.Random


class MainActivity : ComponentActivity() {

    private lateinit var robot: Robot
    private lateinit var webView: WebView
    private lateinit var libraryConfig: LibraryConfig

    // LAN admin panel / remote driving
    private lateinit var cameraStreamer: CameraStreamer
    private lateinit var adminServer: AdminServer

    /*
     * Main navigation / training overlay.
     */
    private lateinit var navigationOverlay: FrameLayout
    private lateinit var navigationCard: LinearLayout

    private lateinit var navigationTitle: TextView
    private lateinit var navigationShelf: TextView
    private lateinit var navigationStatus: TextView

    private lateinit var saveShelfButton: Button
    private lateinit var cancelShelfButton: Button

    private lateinit var assistanceYesButton: Button
    private lateinit var assistanceNoButton: Button

    private lateinit var robotAnimation: RobotAnimationView


    /*
     * Navigation state.
     */
    private var goingToShelf = false
    private var returningHome = false


    /*
     * True when we are currently teaching temi
     * a previously unknown shelf.
     */
    private var teachingShelf = false


    /*
     * Shelf currently being taught.
     */
    private var shelfBeingTaught: String? = null


    /*
     * Persistent local shelf database.
     */
    private lateinit var shelfDatabase: ShelfDatabase


    companion object {

        private const val WEBSITE_SCALE = 1.5


        /*
         * This must exactly match the saved location
         * name on your temi.
         */
        private const val HOME_BASE_LOCATION =
            "home base"


        private const val SETTINGS_PERMISSION_REQUEST_CODE =
            1001

        private const val CAMERA_PERMISSION_REQUEST_CODE =
            1002


        private const val TABLET_UP_ANGLE = 55

        private const val TABLET_TILT_SPEED = 1f
    }


    // =========================================================
    // SHELF DATABASE
    // =========================================================

    private class ShelfDatabase(
        context: Context
    ) {

        private val preferences =
            context.getSharedPreferences(
                "kirjastobotti_shelves",
                Context.MODE_PRIVATE
            )


        fun save(
            shelf: String,
            position: Position
        ) {

            preferences.edit()
                .putString(
                    shelf,
                    "${position.x}|${position.y}|${position.yaw}"
                )
                .apply()
        }


        fun get(
            shelf: String
        ): Position? {

            val value =
                preferences.getString(
                    shelf,
                    null
                )
                    ?: return null


            return try {

                val parts =
                    value.split("|")


                if (parts.size < 3) {
                    return null
                }


                Position(
                    parts[0].toFloat(),
                    parts[1].toFloat(),
                    parts[2].toFloat()
                )

            } catch (
                e: Exception
            ) {

                e.printStackTrace()

                null
            }
        }


        fun contains(
            shelf: String
        ): Boolean {

            return preferences.contains(
                shelf
            )
        }


        fun count(): Int {

            return preferences.all.size
        }
    }


    // =========================================================
    // CUSTOM ROBOT ANIMATION
    // =========================================================

    private class RobotAnimationView(
        context: Context
    ) : View(context) {

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val facePaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val path =
            Path()


        private var running =
            false


        private var animationStartTime =
            0L


        /*
         * Random movement timing.
         */
        private var movementDuration =
            1800L


        private var movementPause =
            500L


        private var nextMovementChange =
            0L


        private var movementDirection =
            1f


        private var movementPosition =
            0f


        init {

            paint.strokeCap =
                Paint.Cap.ROUND

            paint.strokeJoin =
                Paint.Join.ROUND


            facePaint.strokeCap =
                Paint.Cap.ROUND

            facePaint.strokeJoin =
                Paint.Join.ROUND


            setLayerType(
                View.LAYER_TYPE_SOFTWARE,
                null
            )
        }


        fun startAnimation() {

            running =
                true


            animationStartTime =
                System.currentTimeMillis()


            movementPosition =
                0f


            movementDirection =
                1f


            movementDuration =
                Random.nextLong(
                    1500L,
                    2300L
                )


            movementPause =
                Random.nextLong(
                    350L,
                    850L
                )


            nextMovementChange =
                animationStartTime +
                        movementDuration


            invalidate()
        }


        fun stopAnimation() {

            running =
                false

            invalidate()
        }


        private fun updateMovement(
            now: Long
        ) {

            if (!running) {
                return
            }


            if (
                now >=
                nextMovementChange
            ) {

                movementDirection *=
                    -1f


                movementDuration =
                    Random.nextLong(
                        1400L,
                        2400L
                    )


                movementPause =
                    Random.nextLong(
                        300L,
                        850L
                    )


                nextMovementChange =
                    now +
                            movementDuration +
                            movementPause
            }


            val remaining =
                nextMovementChange - now


            val progress =
                if (
                    remaining >
                    movementPause
                ) {

                    val movementTime =
                        movementDuration.toFloat()


                    val elapsed =
                        (
                                movementDuration +
                                        movementPause -
                                        remaining
                                )
                            .coerceAtLeast(0L)
                            .toFloat()


                    (
                            elapsed /
                                    movementTime
                            )
                        .coerceIn(
                            0f,
                            1f
                        )

                } else {

                    1f
                }


            /*
             * Smoothstep.
             */
            val smooth =
                progress *
                        progress *
                        (
                                3f -
                                        2f *
                                        progress
                                )


            movementPosition =
                if (
                    movementDirection >
                    0
                ) {

                    smooth

                } else {

                    1f - smooth
                }
        }


        override fun onDraw(
            canvas: Canvas
        ) {

            super.onDraw(
                canvas
            )


            if (
                width <= 0 ||
                height <= 0
            ) {
                return
            }


            val now =
                System.currentTimeMillis()


            updateMovement(
                now
            )


            val scale =
                minOf(
                    width / 260f,
                    height / 230f
                )


            val movement =
                (
                        movementPosition -
                                0.5f
                        ) *
                        20f *
                        scale


            val elapsed =
                if (running) {

                    now -
                            animationStartTime

                } else {

                    0L
                }


            val bob =
                sin(
                    elapsed /
                            700.0 *
                            Math.PI *
                            2.0
                ).toFloat() *
                        1.2f *
                        scale


            val centerX =
                width / 2f +
                        movement


            val centerY =
                height / 2f +
                        bob


            canvas.save()


            // =====================================================
            // SHADOW
            // =====================================================

            paint.style =
                Paint.Style.FILL

            paint.color =
                0x30000000


            val shadow =
                RectF(
                    centerX - 56f * scale,
                    centerY + 76f * scale,
                    centerX + 56f * scale,
                    centerY + 88f * scale
                )


            canvas.drawOval(
                shadow,
                paint
            )


            // =====================================================
            // BODY
            // =====================================================

            paint.style =
                Paint.Style.FILL

            paint.color =
                0xFFF7F9FB.toInt()


            val body =
                RectF(
                    centerX - 62f * scale,
                    centerY - 12f * scale,
                    centerX + 62f * scale,
                    centerY + 75f * scale
                )


            canvas.drawRoundRect(
                body,
                22f * scale,
                22f * scale,
                paint
            )


            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                5f * scale

            paint.color =
                0xFF101820.toInt()


            canvas.drawRoundRect(
                body,
                22f * scale,
                22f * scale,
                paint
            )


            // =====================================================
            // BODY SCREEN
            // =====================================================

            paint.style =
                Paint.Style.FILL

            paint.color =
                0xFFE9EFF3.toInt()


            val screen =
                RectF(
                    centerX - 43f * scale,
                    centerY + 8f * scale,
                    centerX + 43f * scale,
                    centerY + 59f * scale
                )


            canvas.drawRoundRect(
                screen,
                13f * scale,
                13f * scale,
                paint
            )


            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                4f * scale

            paint.color =
                0xFF101820.toInt()


            canvas.drawRoundRect(
                screen,
                13f * scale,
                13f * scale,
                paint
            )


            path.reset()


            path.moveTo(
                centerX - 42f * scale,
                centerY + 38f * scale
            )


            path.cubicTo(
                centerX - 25f * scale,
                centerY + 38f * scale,
                centerX - 26f * scale,
                centerY + 20f * scale,
                centerX - 10f * scale,
                centerY + 20f * scale
            )


            path.cubicTo(
                centerX + 7f * scale,
                centerY + 20f * scale,
                centerX + 11f * scale,
                centerY + 45f * scale,
                centerX + 43f * scale,
                centerY + 45f * scale
            )


            canvas.drawPath(
                path,
                paint
            )


            // =====================================================
            // HEAD
            // =====================================================

            paint.style =
                Paint.Style.FILL

            paint.color =
                0xFFF7F9FB.toInt()


            val head =
                RectF(
                    centerX - 48f * scale,
                    centerY - 81f * scale,
                    centerX + 48f * scale,
                    centerY + 1f * scale
                )


            canvas.drawRoundRect(
                head,
                17f * scale,
                17f * scale,
                paint
            )


            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                5f * scale

            paint.color =
                0xFF101820.toInt()


            canvas.drawRoundRect(
                head,
                17f * scale,
                17f * scale,
                paint
            )


            // =====================================================
            // FACE
            // =====================================================

            val face =
                RectF(
                    centerX - 34f * scale,
                    centerY - 64f * scale,
                    centerX + 34f * scale,
                    centerY - 13f * scale
                )


            paint.strokeWidth =
                4f * scale


            canvas.drawRoundRect(
                face,
                11f * scale,
                11f * scale,
                paint
            )


            facePaint.color =
                0xFF101820.toInt()

            facePaint.strokeWidth =
                4f * scale


            canvas.drawLine(
                centerX - 17f * scale,
                centerY - 52f * scale,
                centerX - 17f * scale,
                centerY - 43f * scale,
                facePaint
            )


            canvas.drawLine(
                centerX + 17f * scale,
                centerY - 52f * scale,
                centerX + 17f * scale,
                centerY - 43f * scale,
                facePaint
            )


            path.reset()


            path.moveTo(
                centerX - 19f * scale,
                centerY - 34f * scale
            )


            path.cubicTo(
                centerX - 10f * scale,
                centerY - 27f * scale,
                centerX - 5f * scale,
                centerY - 23f * scale,
                centerX,
                centerY - 23f * scale
            )


            path.cubicTo(
                centerX + 6f * scale,
                centerY - 23f * scale,
                centerX + 12f * scale,
                centerY - 29f * scale,
                centerX + 19f * scale,
                centerY - 34f * scale
            )


            canvas.drawPath(
                path,
                facePaint
            )


            // =====================================================
            // ANTENNA
            // =====================================================

            paint.strokeWidth =
                4f * scale

            paint.color =
                0xFF101820.toInt()


            canvas.drawLine(
                centerX,
                centerY - 81f * scale,
                centerX,
                centerY - 101f * scale,
                paint
            )


            paint.style =
                Paint.Style.FILL


            canvas.drawCircle(
                centerX,
                centerY - 105f * scale,
                4f * scale,
                paint
            )


            // =====================================================
            // WHEELS
            // =====================================================

            val leftWheelX =
                centerX - 36f * scale

            val rightWheelX =
                centerX + 36f * scale

            val wheelY =
                centerY + 80f * scale


            paint.style =
                Paint.Style.FILL

            paint.color =
                0xFFF7F9FB.toInt()


            canvas.drawCircle(
                leftWheelX,
                wheelY,
                19f * scale,
                paint
            )


            canvas.drawCircle(
                rightWheelX,
                wheelY,
                19f * scale,
                paint
            )


            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                5f * scale

            paint.color =
                0xFF101820.toInt()


            canvas.drawCircle(
                leftWheelX,
                wheelY,
                19f * scale,
                paint
            )


            canvas.drawCircle(
                rightWheelX,
                wheelY,
                19f * scale,
                paint
            )


            val wheelRotation =
                elapsed /
                        55f


            canvas.save()


            canvas.rotate(
                wheelRotation,
                leftWheelX,
                wheelY
            )


            paint.strokeWidth =
                3f * scale


            canvas.drawLine(
                leftWheelX - 9f * scale,
                wheelY,
                leftWheelX + 9f * scale,
                wheelY,
                paint
            )


            canvas.drawLine(
                leftWheelX,
                wheelY - 9f * scale,
                leftWheelX,
                wheelY + 9f * scale,
                paint
            )


            canvas.restore()


            canvas.save()


            canvas.rotate(
                wheelRotation,
                rightWheelX,
                wheelY
            )


            canvas.drawLine(
                rightWheelX - 9f * scale,
                wheelY,
                rightWheelX + 9f * scale,
                wheelY,
                paint
            )


            canvas.drawLine(
                rightWheelX,
                wheelY - 9f * scale,
                rightWheelX,
                wheelY + 9f * scale,
                paint
            )


            canvas.restore()


            canvas.restore()


            if (running) {

                postInvalidateOnAnimation()
            }
        }
    }


    // =========================================================
    // TEMI FOLLOW LISTENER
    // =========================================================

    private val followListener =
        object : OnBeWithMeStatusChangedListener {

            override fun onBeWithMeStatusChanged(
                status: String
            ) {

                println(
                    "Kirjastobotti follow status: $status"
                )


                runOnUiThread {

                    if (!teachingShelf) {
                        return@runOnUiThread
                    }


                    when (
                        status.lowercase()
                    ) {

                        "search" -> {

                            navigationStatus.text =
                                "Etsin sinua..."
                        }


                        "start" -> {

                            navigationStatus.text =
                                "Seuraan sinua."
                        }


                        "track" -> {

                            navigationStatus.text =
                                "Seuraan sinua.\n" +
                                        "Vie minut oikean hyllyn luo."
                        }


                        "calculating" -> {

                            navigationStatus.text =
                                "Lasketaan reittiä..."
                        }


                        "obstacle detected" -> {

                            navigationStatus.text =
                                "Este havaittu."
                        }


                        "abort" -> {

                            if (teachingShelf) {

                                navigationStatus.text =
                                    "Seuraaminen keskeytyi."
                            }
                        }
                    }
                }
            }
        }


    // =========================================================
    // TEMI NAVIGATION LISTENER
    // =========================================================

    private val navigationListener =
        object : OnGoToLocationStatusChangedListener {

            override fun onGoToLocationStatusChanged(
                location: String,
                status: String,
                descriptionId: Int,
                description: String
            ) {

                println(
                    "Kirjastobotti navigation: " +
                            "location=$location " +
                            "status=$status " +
                            "description=$description"
                )


                if (
                    status ==
                    OnGoToLocationStatusChangedListener.COMPLETE
                ) {

                    runOnUiThread {

                        tiltTabletUp()
                    }


                    if (
                        goingToShelf &&
                        !returningHome
                    ) {

                        goingToShelf =
                            false


                        returningHome =
                            false


                        runOnUiThread {

                            showArrivedScreen()
                        }


                        return
                    }


                    if (returningHome) {

                        goingToShelf =
                            false

                        returningHome =
                            false


                        runOnUiThread {

                            tiltTabletUp()

                            hideNavigationScreen()


                            Toast.makeText(
                                this@MainActivity,
                                "Palattu kotiin.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }


                else if (
                    status ==
                    OnGoToLocationStatusChangedListener.ABORT
                ) {

                    goingToShelf =
                        false

                    returningHome =
                        false


                    runOnUiThread {

                        tiltTabletUp()


                        navigationStatus.text =
                            "Navigointi keskeytyi"


                        Toast.makeText(
                            this@MainActivity,
                            "Navigointi keskeytyi: $description",
                            Toast.LENGTH_LONG
                        ).show()


                        window.decorView.postDelayed(
                            {

                                hideNavigationScreen()

                            },
                            1800
                        )
                    }
                }
            }
        }


    // =========================================================
    // CREATE
    // =========================================================

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )

        libraryConfig =
            LibraryConfig(this)


        robot =
            Robot.getInstance()


        cameraStreamer =
            CameraStreamer(this)

        adminServer =
            AdminServer(
                this,
                robot,
                cameraStreamer
            )

        adminServer.start()


        if (
            checkSelfPermission(
                Manifest.permission.CAMERA
            ) ==
            PackageManager.PERMISSION_GRANTED
        ) {

            cameraStreamer.start()

        } else {

            requestPermissions(
                arrayOf(
                    Manifest.permission.CAMERA
                ),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }


        shelfDatabase =
            ShelfDatabase(this)


        robot.addOnGoToLocationStatusChangedListener(
            navigationListener
        )


        robot.addOnBeWithMeStatusChangedListener(
            followListener
        )


        disableTemiNavigationBillboard()

        hideTemiUI()

        createScreen()

        setupBackNavigation()
    }


    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )


        if (
            requestCode ==
            CAMERA_PERMISSION_REQUEST_CODE &&
            grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED
        ) {

            cameraStreamer.start()
        }
    }


    override fun onResume() {

        super.onResume()

        disableTemiNavigationBillboard()

        hideTemiUI()
    }


    // =========================================================
    // DISABLE TEMI NAVIGATION BILLBOARD
    // =========================================================

    private fun disableTemiNavigationBillboard() {

        try {

            val permissionResult =
                robot.checkSelfPermission(
                    Permission.SETTINGS
                )


            if (
                permissionResult ==
                Permission.GRANTED
            ) {

                robot.toggleNavigationBillboard(
                    true
                )

            } else {

                requestSettingsPermission()
            }

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }


    private fun requestSettingsPermission() {

        try {

            robot.requestPermissions(
                listOf(
                    Permission.SETTINGS
                ),
                SETTINGS_PERMISSION_REQUEST_CODE
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()


            Toast.makeText(
                this,
                "Temi SETTINGS-permission tarvitaan.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    // =========================================================
    // TABLET / HEAD UP
    // =========================================================

    private fun tiltTabletUp() {

        try {

            robot.tiltAngle(
                TABLET_UP_ANGLE,
                TABLET_TILT_SPEED
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }
    }


    // =========================================================
    // BACK NAVIGATION
    // =========================================================

    private fun setupBackNavigation() {

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {

                override fun handleOnBackPressed() {

                    if (teachingShelf) {
                        return
                    }


                    if (
                        goingToShelf ||
                        returningHome
                    ) {

                        return
                    }


                    if (
                        webView.canGoBack()
                    ) {

                        webView.goBack()

                    } else {

                        isEnabled =
                            false

                        onBackPressedDispatcher
                            .onBackPressed()
                    }
                }
            }
        )
    }


    // =========================================================
    // FINNA FILTER
    // =========================================================

    private fun applyAlwaysFilter(
        url: String
    ): String {

        try {

            val uri =
                Uri.parse(url)


            if (
                uri.host !=
                "outi.finna.fi"
            ) {

                return url
            }


            val existingFilters =
                uri.getQueryParameters(
                    "filter[]"
                )


            if (
                existingFilters.any {
                    it ==
                            libraryConfig.alwaysFilter
                }
            ) {

                return url
            }


            return uri
                .buildUpon()
                .appendQueryParameter(
                    "filter[]",
                    libraryConfig.alwaysFilter
                )
                .build()
                .toString()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()

            return url
        }
    }


    // =========================================================
    // CREATE SCREEN
    // =========================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun createScreen() {

        val root =
            FrameLayout(this)


        root.setBackgroundColor(
            Color.BLACK
        )


        webView =
            WebView(this)


        webView.setBackgroundColor(
            Color.BLACK
        )


        webView.settings.apply {

            javaScriptEnabled =
                true

            domStorageEnabled =
                true

            databaseEnabled =
                true

            loadsImagesAutomatically =
                true

            useWideViewPort =
                true

            loadWithOverviewMode =
                false

            setSupportZoom(
                false
            )

            builtInZoomControls =
                false

            displayZoomControls =
                false

            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            mediaPlaybackRequiresUserGesture =
                false

            textZoom =
                100
        }


        webView.addJavascriptInterface(
            AndroidBridge(),
            "Android"
        )


        webView.webViewClient =
            object : WebViewClient() {

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {

                    val url =
                        request?.url
                            ?.toString()
                            ?: return false


                    val filteredUrl =
                        applyAlwaysFilter(
                            url
                        )


                    if (
                        filteredUrl != url
                    ) {

                        view?.loadUrl(
                            filteredUrl
                        )

                        return true
                    }


                    return false
                }


                override fun onPageFinished(
                    view: WebView?,
                    url: String?
                ) {

                    super.onPageFinished(
                        view,
                        url
                    )


                    if (
                        url != null &&
                        url.startsWith(
                            libraryConfig.websiteUrl
                        )
                    ) {

                        val filteredUrl =
                            applyAlwaysFilter(
                                url
                            )


                        if (
                            filteredUrl != url
                        ) {

                            view?.loadUrl(
                                filteredUrl
                            )

                            return
                        }
                    }


                    customizeWebsite()
                }
            }


        webView.webChromeClient =
            WebChromeClient()


        root.addView(
            webView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )


        createNavigationOverlay(
            root
        )


        setContentView(
            root
        )


        webView.loadUrl(
            applyAlwaysFilter(
                libraryConfig.websiteUrl
            )
        )
    }


    // =========================================================
    // CREATE NAVIGATION OVERLAY
    // =========================================================

    private fun createNavigationOverlay(
        root: FrameLayout
    ) {

        navigationOverlay =
            FrameLayout(this)


        navigationOverlay.setBackgroundColor(
            0xCC05080C.toInt()
        )


        navigationOverlay.visibility =
            View.GONE


        navigationOverlay.elevation =
            1000f


        navigationCard =
            LinearLayout(this)


        navigationCard.orientation =
            LinearLayout.VERTICAL


        navigationCard.gravity =
            Gravity.CENTER_HORIZONTAL


        navigationCard.setPadding(
            55,
            40,
            55,
            45
        )


        navigationCard.background =
            roundedBackground(
                0xFF101820.toInt(),
                32f
            )


        navigationCard.elevation =
            30f


        val cardParams =
            FrameLayout.LayoutParams(
                780,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )


        cardParams.gravity =
            Gravity.CENTER


        navigationOverlay.addView(
            navigationCard,
            cardParams
        )


        robotAnimation =
            RobotAnimationView(this)


        navigationCard.addView(
            robotAnimation,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                245
            )
        )


        navigationTitle =
            TextView(this)


        navigationTitle.textSize =
            38f


        navigationTitle.setTextColor(
            Color.WHITE
        )


        navigationTitle.gravity =
            Gravity.CENTER


        navigationTitle.setTypeface(
            null,
            android.graphics.Typeface.BOLD
        )


        navigationCard.addView(
            navigationTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )


        navigationShelf =
            TextView(this)


        navigationShelf.textSize =
            28f


        navigationShelf.setTextColor(
            0xFF8DD8FF.toInt()
        )


        navigationShelf.gravity =
            Gravity.CENTER


        val shelfParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )


        shelfParams.topMargin =
            15

        shelfParams.bottomMargin =
            15


        navigationCard.addView(
            navigationShelf,
            shelfParams
        )


        navigationStatus =
            TextView(this)


        navigationStatus.textSize =
            22f


        navigationStatus.setTextColor(
            0xFFB8C4CC.toInt()
        )


        navigationStatus.gravity =
            Gravity.CENTER


        navigationCard.addView(
            navigationStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )


        saveShelfButton =
            createButton(
                "TALLENNA HYLLY TÄHÄN"
            )


        saveShelfButton.setOnClickListener {

            saveCurrentShelf()
        }


        val saveParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                75
            )


        saveParams.topMargin =
            30


        navigationCard.addView(
            saveShelfButton,
            saveParams
        )


        cancelShelfButton =
            createSecondaryButton(
                "PERUUTA"
            )


        cancelShelfButton.setOnClickListener {

            if (teachingShelf) {

                cancelShelfTeaching()

            } else {

                cancelNavigation()
            }
        }


        val cancelParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                70
            )


        cancelParams.topMargin =
            12


        navigationCard.addView(
            cancelShelfButton,
            cancelParams
        )


        assistanceYesButton =
            createButton(
                "KYLLÄ, TARVITSEN AVUSTUSTA"
            )


        assistanceYesButton.setOnClickListener {

            goingToShelf =
                false

            returningHome =
                false

            hideNavigationScreen()
        }


        val yesParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                75
            )


        yesParams.topMargin =
            30


        navigationCard.addView(
            assistanceYesButton,
            yesParams
        )


        assistanceNoButton =
            createSecondaryButton(
                "EI, PALAUTA KOTIIN"
            )


        assistanceNoButton.setOnClickListener {

            returnHomeFromShelf()
        }


        val noParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                70
            )


        noParams.topMargin =
            12


        navigationCard.addView(
            assistanceNoButton,
            noParams
        )


        saveShelfButton.visibility =
            View.GONE

        cancelShelfButton.visibility =
            View.GONE

        assistanceYesButton.visibility =
            View.GONE

        assistanceNoButton.visibility =
            View.GONE


        root.addView(
            navigationOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }


    // =========================================================
    // BUTTON HELPERS
    // =========================================================

    private fun createButton(
        text: String
    ): Button {

        val button =
            Button(this)


        button.text =
            text


        button.textSize =
            18f


        button.setTextColor(
            Color.WHITE
        )


        button.isAllCaps =
            false


        button.background =
            roundedBackground(
                0xFF1976A8.toInt(),
                18f
            )


        return button
    }


    private fun createSecondaryButton(
        text: String
    ): Button {

        val button =
            Button(this)


        button.text =
            text


        button.textSize =
            18f


        button.setTextColor(
            Color.WHITE
        )


        button.isAllCaps =
            false


        button.background =
            roundedBackground(
                0xFF26343D.toInt(),
                18f
            )


        return button
    }


    private fun roundedBackground(
        color: Int,
        radius: Float
    ): android.graphics.drawable.GradientDrawable {

        return android.graphics.drawable.GradientDrawable()
            .apply {

                setColor(
                    color
                )

                cornerRadius =
                    radius
            }
    }


    // =========================================================
    // START SHELF REQUEST
    // =========================================================

    private fun requestShelf(
        shelf: String
    ) {

        val savedPosition =
            shelfDatabase.get(
                shelf
            )


        if (
            savedPosition != null
        ) {

            Toast.makeText(
                this,
                "Hylly löytyy tietokannasta.",
                Toast.LENGTH_SHORT
            ).show()


            goToSavedShelf(
                shelf,
                savedPosition
            )


            return
        }


        startShelfTeaching(
            shelf
        )
    }


    // =========================================================
    // START SHELF TEACHING
    // =========================================================

    private fun startShelfTeaching(
        shelf: String
    ) {

        shelfBeingTaught =
            shelf


        teachingShelf =
            true


        goingToShelf =
            false


        returningHome =
            false


        showTeachingScreen(
            shelf
        )


        try {

            robot.stopMovement()

            tiltTabletUp()

            robot.beWithMe()


            println(
                "Kirjastobotti: follow mode started for shelf=$shelf"
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()


            teachingShelf =
                false


            shelfBeingTaught =
                null


            hideNavigationScreen()


            Toast.makeText(
                this,
                "Follow-tilan käynnistys epäonnistui: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun showTeachingScreen(
        shelf: String
    ) {

        navigationTitle.text =
            "Opetetaan uusi hylly"


        navigationShelf.text =
            shelf


        navigationStatus.text =
            "Seuraan sinua.\n\n" +
                    "Vie minut oikean hyllyn kohdalle."


        saveShelfButton.text =
            "TALLENNA HYLLY TÄHÄN"


        saveShelfButton.visibility =
            View.VISIBLE


        cancelShelfButton.visibility =
            View.VISIBLE


        assistanceYesButton.visibility =
            View.GONE


        assistanceNoButton.visibility =
            View.GONE


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()


        robotAnimation.startAnimation()
    }


    private fun saveCurrentShelf() {

        val shelf =
            shelfBeingTaught


        if (
            shelf.isNullOrBlank()
        ) {

            Toast.makeText(
                this,
                "Tallennettava hylly puuttuu.",
                Toast.LENGTH_LONG
            ).show()

            return
        }


        try {

            val position =
                robot.getPosition()


            println(
                "Kirjastobotti: saving shelf=$shelf " +
                        "x=${position.x} " +
                        "y=${position.y} " +
                        "yaw=${position.yaw}"
            )


            robot.stopMovement()


            shelfDatabase.save(
                shelf,
                position
            )


            teachingShelf =
                false


            shelfBeingTaught =
                null


            navigationTitle.text =
                "Hylly tallennettu"


            navigationShelf.text =
                shelf


            navigationStatus.text =
                "Sijainti tallennettu onnistuneesti.\n\n" +
                        "Voit jatkaa tästä normaalisti."


            saveShelfButton.visibility =
                View.GONE


            cancelShelfButton.visibility =
                View.VISIBLE


            returningHome =
                false

            goingToShelf =
                false


            Toast.makeText(
                this,
                "Hylly tallennettu: $shelf",
                Toast.LENGTH_LONG
            ).show()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()


            Toast.makeText(
                this,
                "Hyllyn tallennus epäonnistui: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun cancelShelfTeaching() {

        try {

            robot.stopMovement()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }


        teachingShelf =
            false


        shelfBeingTaught =
            null


        goingToShelf =
            false


        returningHome =
            false


        tiltTabletUp()


        hideNavigationScreen()


        Toast.makeText(
            this,
            "Hyllyn tallennus peruutettu.",
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun goToSavedShelf(
        shelf: String,
        position: Position
    ) {

        try {

            disableTemiNavigationBillboard()


            goingToShelf =
                true


            returningHome =
                false


            teachingShelf =
                false


            showNavigationScreen(
                shelf
            )


            navigationStatus.text =
                "Navigoidaan tallennettuun sijaintiin..."


            robot.goToPosition(
                position,
                false,
                null,
                null,
                true
            )

        } catch (
            e: Exception
        ) {

            goingToShelf =
                false


            returningHome =
                false


            hideNavigationScreen()


            Toast.makeText(
                this,
                "Navigointivirhe: ${e.message}",
                Toast.LENGTH_LONG
            ).show()


            e.printStackTrace()
        }
    }


    private fun showNavigationScreen(
        shelf: String
    ) {

        navigationTitle.text =
            "Menossa hyllylle"


        navigationShelf.text =
            shelf


        navigationStatus.text =
            "Navigoidaan..."


        saveShelfButton.visibility =
            View.GONE


        cancelShelfButton.text =
            "PERUUTA"


        cancelShelfButton.visibility =
            View.VISIBLE


        assistanceYesButton.visibility =
            View.GONE


        assistanceNoButton.visibility =
            View.GONE


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()


        robotAnimation.startAnimation()
    }


    private fun showArrivedScreen() {

        robotAnimation.stopAnimation()


        navigationTitle.text =
            "Saavuttu!"


        navigationShelf.text =
            ""


        navigationStatus.text =
            "Tarvitsetko vielä avustusta?"


        saveShelfButton.visibility =
            View.GONE


        cancelShelfButton.visibility =
            View.GONE


        assistanceYesButton.visibility =
            View.VISIBLE


        assistanceNoButton.visibility =
            View.VISIBLE


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()
    }


    private fun cancelNavigation() {

        try {

            robot.stopMovement()

        } catch (
            e: Exception
        ) {

            e.printStackTrace()
        }


        goingToShelf =
            false


        returningHome =
            false


        teachingShelf =
            false


        tiltTabletUp()


        hideNavigationScreen()


        Toast.makeText(
            this,
            "Navigointi peruutettu.",
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun returnHomeFromShelf() {

        try {

            robot.stopMovement()


            goingToShelf =
                false


            returningHome =
                true


            navigationTitle.text =
                "Palaan kotiin"


            navigationShelf.text =
                ""


            navigationStatus.text =
                "Palaan kotiasemalle..."


            saveShelfButton.visibility =
                View.GONE


            cancelShelfButton.text =
                "PERUUTA"


            cancelShelfButton.visibility =
                View.VISIBLE


            assistanceYesButton.visibility =
                View.GONE


            assistanceNoButton.visibility =
                View.GONE


            navigationOverlay.visibility =
                View.VISIBLE


            navigationOverlay.bringToFront()


            robotAnimation.startAnimation()


            robot.goTo(
                HOME_BASE_LOCATION
            )

        } catch (
            e: Exception
        ) {

            e.printStackTrace()


            returningHome =
                false


            hideNavigationScreen()


            Toast.makeText(
                this,
                "Kotiinpaluu epäonnistui: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun hideNavigationScreen() {

        if (
            ::robotAnimation.isInitialized
        ) {

            robotAnimation.stopAnimation()
        }


        navigationOverlay.visibility =
            View.GONE
    }


    // =========================================================
    // CUSTOMIZE WEBSITE
    // =========================================================

    private fun customizeWebsite() {

        /*
         * Escape the configured library name before putting it
         * into the JavaScript string.
         */
        val escapedLibraryBranchName =
            libraryConfig.libraryBranchName
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")


        val javascript = """
            (function() {

                console.log(
                    "Kirjastobotti: starting"
                );


                function cleanText(text) {

                    if (!text) {
                        return "";
                    }

                    return text
                        .replace(/\u00a0/g, " ")
                        .replace(/\s+/g, " ")
                        .trim()
                        .toLowerCase();
                }


                /*
                 * Library branch comes from LibraryConfig.kt.
                 *
                 * Example:
                 * "Oulun keskustakirjasto Saari"
                 */
                const libraryBranchName =
                    cleanText(
                        "$escapedLibraryBranchName"
                    );


                function getBookContainer(element) {

                    if (!element) {
                        return null;
                    }


                    const record =
                        element.closest(
                            ".record-container"
                        );


                    if (record) {
                        return record;
                    }


                    const result =
                        element.closest(
                            ".result"
                        );


                    if (result) {
                        return result;
                    }


                    const media =
                        element.closest(
                            ".media"
                        );


                    if (media) {
                        return media;
                    }


                    return null;
                }


                function getBookId(record) {

                    if (!record) {
                        return "";
                    }


                    const hiddenId =
                        record.querySelector(
                            ".hiddenId"
                        );


                    if (!hiddenId) {
                        return "";
                    }


                    return (
                        hiddenId.value ||
                        hiddenId.getAttribute("value") ||
                        ""
                    );
                }


                /*
                 * Same old shelf logic as getSaariShelf(),
                 * except the branch name now comes from
                 * LibraryConfig.kt.
                 */
                function getLibraryShelf(record) {

                    if (!record) {
                        return null;
                    }


                    const locations =
                        record.querySelectorAll(
                            ".no-branches"
                        );


                    for (
                        let i = 0;
                        i < locations.length;
                        i++
                    ) {

                        const location =
                            locations[i];


                        const branch =
                            location.querySelector(
                                ".branch"
                            );


                        if (!branch) {
                            continue;
                        }


                        const branchName =
                            cleanText(
                                branch.innerText ||
                                branch.textContent
                            );


                        if (
                            branchName.includes(
                                libraryBranchName
                            )
                        ) {

                            const callNumber =
                                location.querySelector(
                                    ".callnumber-text"
                                );


                            if (!callNumber) {
                                continue;
                            }


                            let shelf =
                                callNumber.innerText ||
                                callNumber.textContent ||
                                "";


                            shelf =
                                shelf
                                    .replace(
                                        /\u00a0/g,
                                        " "
                                    )
                                    .replace(
                                        /\s+/g,
                                        " "
                                    )
                                    .trim();


                            shelf =
                                shelf.replace(
                                    /^hylly:\s*/i,
                                    ""
                                );


                            shelf =
                                shelf.trim();


                            if (shelf) {
                                return shelf;
                            }
                        }
                    }


                    return null;
                }


                function replaceReservationButton(
                    button
                ) {

                    if (!button) {
                        return;
                    }


                    if (
                        button.dataset
                            .kirjastobottiProcessed ===
                        "true"
                    ) {

                        return;
                    }


                    const text =
                        cleanText(
                            button.innerText ||
                            button.textContent
                        );


                    if (
                        !text.includes(
                            "kirjaudu sisään varataksesi"
                        )
                    ) {

                        return;
                    }


                    const record =
                        getBookContainer(
                            button
                        );


                    if (!record) {
                        return;
                    }


                    const shelf =
                        getLibraryShelf(
                            record
                        );


                    if (!shelf) {
                        return;
                    }


                    const bookId =
                        getBookId(
                            record
                        );


                    const cleanButton =
                        button.cloneNode(
                            false
                        );


                    cleanButton.classList.add(
                        "kirjastobotti-button"
                    );


                    cleanButton.dataset
                        .kirjastobottiProcessed =
                        "true";


                    cleanButton.setAttribute(
                        "role",
                        "button"
                    );


                    cleanButton.setAttribute(
                        "type",
                        "button"
                    );


                    cleanButton.removeAttribute(
                        "href"
                    );


                    cleanButton.removeAttribute(
                        "target"
                    );


                    cleanButton.removeAttribute(
                        "data-lightbox"
                    );


                    cleanButton.removeAttribute(
                        "data-lightbox-onclose"
                    );


                    cleanButton.removeAttribute(
                        "data-bs-toggle"
                    );


                    cleanButton.removeAttribute(
                        "data-bs-target"
                    );


                    cleanButton.classList.remove(
                        "login"
                    );


                    cleanButton.textContent =
                        "Vie hyllylle";


                    button.replaceWith(
                        cleanButton
                    );


                    cleanButton.addEventListener(
                        "click",
                        function(event) {

                            event.preventDefault();

                            event.stopPropagation();

                            event.stopImmediatePropagation();


                            Android.requestShelf(
                                shelf
                            );

                        },
                        true
                    );
                }


                function scanForLoginButtons() {

                    const elements =
                        document.querySelectorAll(
                            "a, button"
                        );


                    for (
                        let i = 0;
                        i < elements.length;
                        i++
                    ) {

                        const element =
                            elements[i];


                        const text =
                            cleanText(
                                element.innerText ||
                                element.textContent
                            );


                        if (
                            !text.includes(
                                "kirjaudu sisään varataksesi"
                            )
                        ) {

                            continue;
                        }


                        if (
                            element.dataset
                                .kirjastobottiProcessed ===
                            "true"
                        ) {

                            continue;
                        }


                        replaceReservationButton(
                            element
                        );
                    }
                }


                const oldStyle =
                    document.getElementById(
                        "kirjastobotti-style"
                    );


                if (oldStyle) {
                    oldStyle.remove();
                }


                const style =
                    document.createElement(
                        "style"
                    );


                style.id =
                    "kirjastobotti-style";


                style.innerHTML = `

                    body {
                        zoom: ${WEBSITE_SCALE};
                    }

                    html {
                        overflow-x: hidden !important;
                    }

                    .kirjastobotti-button {
                        display: inline-block !important;
                        visibility: visible !important;
                        opacity: 1 !important;
                        pointer-events: auto !important;
                        cursor: pointer !important;
                    }

                `;


                document.head.appendChild(
                    style
                );


                scanForLoginButtons();


                if (
                    window.kirjastobottiObserver
                ) {

                    window.kirjastobottiObserver
                        .disconnect();

                    window.kirjastobottiObserver =
                        null;
                }


                if (
                    window.kirjastobottiInterval
                ) {

                    clearInterval(
                        window.kirjastobottiInterval
                    );

                    window.kirjastobottiInterval =
                        null;
                }


                let scanTimeout =
                    null;


                function scheduleScan() {

                    if (scanTimeout) {

                        clearTimeout(
                            scanTimeout
                        );
                    }


                    scanTimeout =
                        setTimeout(
                            function() {

                                scanTimeout =
                                    null;

                                scanForLoginButtons();

                            },
                            250
                        );
                }


                window.kirjastobottiObserver =
                    new MutationObserver(
                        function() {

                            scheduleScan();

                        }
                    );


                if (document.body) {

                    window.kirjastobottiObserver
                        .observe(
                            document.body,
                            {
                                childList: true,
                                subtree: true,
                                characterData: true
                            }
                        );
                }


                window.kirjastobottiInterval =
                    setInterval(
                        function() {

                            scanForLoginButtons();

                        },
                        1000
                    );


                console.log(
                    "Kirjastobotti: scanner active"
                );

            })();
        """.trimIndent()


        webView.evaluateJavascript(
            javascript,
            null
        )
    }


    // =========================================================
    // JAVASCRIPT -> ANDROID
    // =========================================================

    inner class AndroidBridge {

        @JavascriptInterface
        fun requestShelf(
            shelf: String
        ) {

            runOnUiThread {

                this@MainActivity.requestShelf(
                    shelf
                )
            }
        }


        @JavascriptInterface
        fun showToast(
            message: String
        ) {

            runOnUiThread {

                Toast.makeText(
                    this@MainActivity,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }


    // =========================================================
    // GO HOME
    // =========================================================

    private fun goHome() {

        try {

            disableTemiNavigationBillboard()


            robot.goTo(
                HOME_BASE_LOCATION
            )

        } catch (
            e: Exception
        ) {

            goingToShelf =
                false

            returningHome =
                false


            runOnUiThread {

                navigationStatus.text =
                    "Kotiinpaluu epäonnistui"


                Toast.makeText(
                    this,
                    "Kotiinpaluu epäonnistui: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }


            e.printStackTrace()
        }
    }


    // =========================================================
    // HIDE TEMI UI
    // =========================================================

    private fun hideTemiUI() {

        try {

            robot.hideTopBar(
                true
            )

        } catch (
            e: Exception
        ) {

            try {

                robot.hideTopBar()

            } catch (
                _: Exception
            ) {

                // Ignore.
            }
        }


        @Suppress("DEPRECATION")
        run {

            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }


    // =========================================================
    // WINDOW FOCUS
    // =========================================================

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(
            hasFocus
        )


        if (hasFocus) {

            hideTemiUI()

            disableTemiNavigationBillboard()


            if (
                ::navigationOverlay.isInitialized &&
                (
                        goingToShelf ||
                                returningHome ||
                                teachingShelf
                        )
            ) {

                navigationOverlay.bringToFront()
            }
        }
    }


    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        try {
            adminServer.stop()
        } catch (
            _: Exception
        ) {
        }


        try {
            cameraStreamer.stop()
        } catch (
            _: Exception
        ) {
        }


        try {

            if (
                teachingShelf ||
                goingToShelf ||
                returningHome
            ) {

                robot.stopMovement()
            }

        } catch (
            _: Exception
        ) {

            // Ignore.
        }


        try {

            robot.removeOnGoToLocationStatusChangedListener(
                navigationListener
            )

        } catch (
            _: Exception
        ) {

            // Ignore.
        }


        try {

            robot.removeOnBeWithMeStatusChangedListener(
                followListener
            )

        } catch (
            _: Exception
        ) {

            // Ignore.
        }


        try {

            webView.evaluateJavascript(
                """
                if (window.kirjastobottiObserver) {
                    window.kirjastobottiObserver.disconnect();
                    window.kirjastobottiObserver = null;
                }

                if (window.kirjastobottiInterval) {
                    clearInterval(window.kirjastobottiInterval);
                    window.kirjastobottiInterval = null;
                }
                """.trimIndent(),
                null
            )

        } catch (
            _: Exception
        ) {

            // WebView may already be closed.
        }


        try {

            webView.stopLoading()

            webView.clearHistory()

            webView.removeAllViews()

            webView.destroy()

        } catch (
            _: Exception
        ) {

            // Ignore.
        }


        super.onDestroy()
    }
}