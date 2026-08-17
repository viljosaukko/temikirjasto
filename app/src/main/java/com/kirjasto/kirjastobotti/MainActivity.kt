package com.kirjasto.kirjastobotti

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
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


class MainActivity : ComponentActivity() {

    private lateinit var robot: Robot
    private lateinit var webView: WebView

    /*
     * Main navigation / training overlay.
     */
    private lateinit var navigationOverlay: FrameLayout
    private lateinit var navigationTitle: TextView
    private lateinit var navigationShelf: TextView
    private lateinit var navigationStatus: TextView

    private lateinit var saveShelfButton: Button
    private lateinit var cancelShelfButton: Button


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
     *
     * IMPORTANT:
     *
     * This is the exact shelf identifier extracted
     * from Finna.
     *
     * We do NOT replace it with coordinates.
     */
    private var shelfBeingTaught: String? = null


    /*
     * Persistent local shelf database.
     *
     * Example:
     *
     * "Lapset, 86.5 ROW"
     *      -> x = 1.5
     *      -> y = -8.0
     *      -> yaw = 90.0
     *
     * The data survives app restarts.
     */
    private lateinit var shelfDatabase: ShelfDatabase


    companion object {

        private const val WEBSITE_URL =
            "https://outi.finna.fi/Search/Results?lookfor=&type=AllFields"

        private const val ALWAYS_FILTER =
            "~building:\"2/Outi/OU/SA/\""

        private const val WEBSITE_SCALE = 1.5


        /*
         * This must exactly match the saved location
         * name on your temi.
         */
        private const val HOME_BASE_LOCATION =
            "home base"


        /*
         * Request code for temi SETTINGS permission.
         */
        private const val SETTINGS_PERMISSION_REQUEST_CODE =
            1001


        /*
         * temi tablet/head upright angle.
         */
        private const val TABLET_UP_ANGLE = 55


        /*
         * Speed used when moving the tablet.
         */
        private const val TABLET_TILT_SPEED = 1f
    }


    // =========================================================
    // SHELF DATABASE
    // =========================================================

    /*
     * Small persistent database using SharedPreferences.
     *
     * No external database library is required.
     */
    private class ShelfDatabase(
        context: Context
    ) {

        private val preferences =
            context.getSharedPreferences(
                "kirjastobotti_shelves",
                Context.MODE_PRIVATE
            )


        /*
         * Store one shelf.
         *
         * Key:
         *     shelf identifier
         *
         * Value:
         *     x|y|yaw
         */
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


        /*
         * Get saved position.
         */
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


        /*
         * Check whether shelf exists.
         */
        fun contains(
            shelf: String
        ): Boolean {

            return preferences.contains(
                shelf
            )
        }


        /*
         * Number of saved shelves.
         */
        fun count(): Int {

            return preferences.all.size
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
                                "Seuraan sinua.\nVie minut oikean hyllyn luo."
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


                    // =================================================
                    // ARRIVED AT SHELF
                    // =================================================

                    if (
                        goingToShelf &&
                        !returningHome
                    ) {

                        goingToShelf = false
                        returningHome = true


                        runOnUiThread {

                            showArrivedScreen()
                        }


                        window.decorView.postDelayed(
                            {

                                goHome()

                            },
                            2500
                        )


                        return
                    }


                    // =================================================
                    // ARRIVED HOME
                    // =================================================

                    if (returningHome) {

                        goingToShelf = false
                        returningHome = false


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


                // =================================================
                // NAVIGATION ABORTED
                // =================================================

                else if (
                    status ==
                    OnGoToLocationStatusChangedListener.ABORT
                ) {

                    goingToShelf = false
                    returningHome = false


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
                            2500
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


        robot =
            Robot.getInstance()


        shelfDatabase =
            ShelfDatabase(this)


        /*
         * Listen for normal navigation.
         */
        robot.addOnGoToLocationStatusChangedListener(
            navigationListener
        )


        /*
         * Listen for follow mode.
         */
        robot.addOnBeWithMeStatusChangedListener(
            followListener
        )


        disableTemiNavigationBillboard()

        hideTemiUI()

        createScreen()

        setupBackNavigation()
    }


    // =========================================================
    // RESUME
    // =========================================================

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


    // =========================================================
    // REQUEST SETTINGS PERMISSION
    // =========================================================

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

                    /*
                     * Do not let Android back interrupt
                     * shelf teaching accidentally.
                     */
                    if (
                        teachingShelf
                    ) {

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

                        isEnabled = false

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
                    it == ALWAYS_FILTER
                }
            ) {

                return url
            }


            return uri
                .buildUpon()
                .appendQueryParameter(
                    "filter[]",
                    ALWAYS_FILTER
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


        // =====================================================
        // WEBVIEW
        // =====================================================

        webView =
            WebView(this)


        webView.setBackgroundColor(
            Color.BLACK
        )


        webView.settings.apply {

            javaScriptEnabled = true

            domStorageEnabled = true

            databaseEnabled = true

            loadsImagesAutomatically = true

            useWideViewPort = true

            loadWithOverviewMode = false

            setSupportZoom(false)

            builtInZoomControls = false

            displayZoomControls = false

            mixedContentMode =
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            mediaPlaybackRequiresUserGesture =
                false

            textZoom = 100
        }


        // =====================================================
        // JAVASCRIPT BRIDGE
        // =====================================================

        webView.addJavascriptInterface(
            AndroidBridge(),
            "Android"
        )


        // =====================================================
        // WEBVIEW CLIENT
        // =====================================================

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
                            WEBSITE_URL
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


        // =====================================================
        // NAVIGATION / TEACHING OVERLAY
        // =====================================================

        createNavigationOverlay(
            root
        )


        setContentView(
            root
        )


        // =====================================================
        // LOAD FINNA
        // =====================================================

        webView.loadUrl(
            applyAlwaysFilter(
                WEBSITE_URL
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
            Color.rgb(
                15,
                25,
                35
            )
        )


        navigationOverlay.visibility =
            View.GONE


        navigationOverlay.elevation =
            1000f


        // =====================================================
        // TITLE
        // =====================================================

        navigationTitle =
            TextView(this)


        navigationTitle.text =
            "Vie minut hyllylle"


        navigationTitle.textSize =
            42f


        navigationTitle.setTextColor(
            Color.WHITE
        )


        navigationTitle.gravity =
            android.view.Gravity.CENTER


        navigationTitle.setTypeface(
            null,
            android.graphics.Typeface.BOLD
        )


        // =====================================================
        // SHELF
        // =====================================================

        navigationShelf =
            TextView(this)


        navigationShelf.text =
            ""


        navigationShelf.textSize =
            34f


        navigationShelf.setTextColor(
            Color.rgb(
                100,
                200,
                255
            )
        )


        navigationShelf.gravity =
            android.view.Gravity.CENTER


        // =====================================================
        // STATUS
        // =====================================================

        navigationStatus =
            TextView(this)


        navigationStatus.text =
            ""


        navigationStatus.textSize =
            25f


        navigationStatus.setTextColor(
            Color.LTGRAY
        )


        navigationStatus.gravity =
            android.view.Gravity.CENTER


        // =====================================================
        // SAVE BUTTON
        // =====================================================

        saveShelfButton =
            Button(this)


        saveShelfButton.text =
            "TALLENNA HYLLY TÄHÄN"


        saveShelfButton.textSize =
            22f


        saveShelfButton.setOnClickListener {

            saveCurrentShelf()
        }


        saveShelfButton.visibility =
            View.GONE


        // =====================================================
        // CANCEL BUTTON
        // =====================================================

        cancelShelfButton =
            Button(this)


        cancelShelfButton.text =
            "PERUUTA"


        cancelShelfButton.textSize =
            20f


        cancelShelfButton.setOnClickListener {

            cancelShelfTeaching()
        }


        // =====================================================
        // VERTICAL CONTENT
        // =====================================================

        val vertical =
            LinearLayout(this)


        vertical.orientation =
            LinearLayout.VERTICAL


        vertical.gravity =
            android.view.Gravity.CENTER


        vertical.setPadding(
            50,
            50,
            50,
            50
        )


        vertical.addView(
            navigationTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )


        val shelfParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )


        shelfParams.topMargin =
            30


        shelfParams.bottomMargin =
            20


        vertical.addView(
            navigationShelf,
            shelfParams
        )


        vertical.addView(
            navigationStatus,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )


        val saveParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )


        saveParams.topMargin =
            45


        saveParams.bottomMargin =
            15


        vertical.addView(
            saveShelfButton,
            saveParams
        )


        vertical.addView(
            cancelShelfButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )


        navigationOverlay.addView(
            vertical,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )


        root.addView(
            navigationOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }


    // =========================================================
    // START SHELF REQUEST
    // =========================================================

    private fun requestShelf(
        shelf: String
    ) {

        /*
         * Check the local database FIRST.
         */
        val savedPosition =
            shelfDatabase.get(
                shelf
            )


        if (
            savedPosition != null
        ) {

            /*
             * -----------------------------------------------
             * SHELF ALREADY KNOWN
             * -----------------------------------------------
             */

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


        /*
         * -----------------------------------------------
         * SHELF NOT KNOWN
         * -----------------------------------------------
         */

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

        /*
         * Remember exactly which shelf we're teaching.
         */
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

            /*
             * Stop anything currently moving.
             */
            robot.stopMovement()


            /*
             * Make sure the tablet is upright.
             */
            tiltTabletUp()


            /*
             * Start temi's real follow mode.
             *
             * The user now walks to the desired shelf
             * and temi follows them.
             */
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


    // =========================================================
    // SHOW TEACHING SCREEN
    // =========================================================

    private fun showTeachingScreen(
        shelf: String
    ) {

        navigationTitle.text =
            "Vie minut hyllylle"


        navigationShelf.text =
            shelf


        navigationStatus.text =
            "Seuraan sinua.\n\n" +
                    "Vie temi oikean hyllyn kohdalle.\n" +
                    "Paina sitten \"TALLENNA HYLLY TÄHÄN\"."


        saveShelfButton.visibility =
            View.VISIBLE


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()
    }


    // =========================================================
    // SAVE CURRENT SHELF
    // =========================================================

    private fun saveCurrentShelf() {

        /*
         * Make absolutely sure we know which shelf
         * is currently being taught.
         */
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

            /*
             * IMPORTANT:
             *
             * Get temi's REAL current map position.
             *
             * This does NOT use any hard-coded coordinate.
             */
            val position =
                robot.getPosition()


            println(
                "Kirjastobotti: saving shelf=$shelf " +
                        "x=${position.x} " +
                        "y=${position.y} " +
                        "yaw=${position.yaw}"
            )


            /*
             * Stop following before saving.
             */
            robot.stopMovement()


            /*
             * Save the shelf permanently.
             */
            shelfDatabase.save(
                shelf,
                position
            )


            /*
             * Clear teaching state.
             */
            teachingShelf =
                false


            shelfBeingTaught =
                null


            /*
             * Update screen.
             */
            navigationTitle.text =
                "Hylly tallennettu!"


            navigationShelf.text =
                shelf


            navigationStatus.text =
                "X: ${position.x}\n" +
                        "Y: ${position.y}\n" +
                        "Yaw: ${position.yaw}\n\n" +
                        "Tallennettu tietokantaan."


            saveShelfButton.visibility =
                View.GONE


            /*
             * Show a toast too.
             */
            Toast.makeText(
                this,
                "Hylly tallennettu: $shelf",
                Toast.LENGTH_LONG
            ).show()


            /*
             * After a short delay, return home.
             */
            window.decorView.postDelayed(
                {

                    returnHomeAfterTeaching()

                },
                2500
            )

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


    // =========================================================
    // RETURN HOME AFTER TEACHING
    // =========================================================

    private fun returnHomeAfterTeaching() {

        try {

            teachingShelf =
                false


            goingToShelf =
                false


            returningHome =
                true


            navigationTitle.text =
                "Hylly tallennettu"


            navigationStatus.text =
                "Palaan kotiin..."


            robot.stopMovement()


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


    // =========================================================
    // CANCEL SHELF TEACHING
    // =========================================================

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


    // =========================================================
    // GO TO SAVED SHELF
    // =========================================================

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


            /*
             * Use the exact position that was saved
             * when the shelf was taught.
             *
             * Yaw is preserved.
             */
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


    // =========================================================
    // SHOW NORMAL NAVIGATION SCREEN
    // =========================================================

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


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()
    }


    // =========================================================
    // SHOW ARRIVED
    // =========================================================

    private fun showArrivedScreen() {

        navigationTitle.text =
            "Saavuttu!"


        navigationShelf.text =
            ""


        navigationStatus.text =
            "Palaan kotiin..."


        saveShelfButton.visibility =
            View.GONE


        navigationOverlay.visibility =
            View.VISIBLE


        navigationOverlay.bringToFront()
    }


    // =========================================================
    // HIDE NAVIGATION SCREEN
    // =========================================================

    private fun hideNavigationScreen() {

        navigationOverlay.visibility =
            View.GONE
    }


    // =========================================================
    // CUSTOMIZE WEBSITE
    // =========================================================

    private fun customizeWebsite() {

        val javascript = """
            (function() {

                console.log(
                    "Kirjastobotti: starting"
                );


                /*
                 * -------------------------------------------------
                 * TEXT HELPERS
                 * -------------------------------------------------
                 */

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
                 * -------------------------------------------------
                 * FINNA RECORD HELPERS
                 * -------------------------------------------------
                 */

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


                function getSaariShelf(record) {

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
                                "oulun keskustakirjasto saari"
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


                /*
                 * -------------------------------------------------
                 * OLD "VIE HYLLYLLE" BUTTONS
                 *
                 * These still work for normal book results:
                 *
                 *   saved shelf -> navigate
                 *   unknown shelf -> old teaching flow
                 * -------------------------------------------------
                 */

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
                        getSaariShelf(
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


                /*
                 * -------------------------------------------------
                 * CSS
                 * -------------------------------------------------
                 */

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


                /*
                 * -------------------------------------------------
                 * WATCH FINNA FOR DYNAMIC RESULTS
                 * -------------------------------------------------
                 */

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

            goingToShelf = false

            returningHome = false


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

            if (
                teachingShelf
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
