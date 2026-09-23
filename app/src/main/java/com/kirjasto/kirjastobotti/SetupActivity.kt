package com.kirjasto.kirjastobotti

import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kirjasto.kirjastobotti.ui.FinnishVirtualKeyboard
import com.kirjasto.kirjastobotti.ui.ShelfListDialog
import com.robotemi.sdk.Robot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * SetupActivity for Temi robot.
 *
 * Provides a dedicated on-screen Shelf Setup Mode:
 * - Direct typing with in-app Finnish keyboard (Å, Ä, Ö, numbers, -, .)
 * - Customizable shortcut buttons above the keyboard
 * - Single-tap "Save shelf at robot position"
 * - Shelf list view for deletion, renaming (without repositioning), and coordinate editing
 * - Optional legacy photo/OCR import
 */
class SetupActivity : ComponentActivity() {

    private lateinit var shelfRangeDb: ShelfRangeDatabase
    private lateinit var setupPrefs: ShelfSetupPreferences
    private lateinit var repo: ShelfRepository

    // State for direct physical keyboard / barcode input
    private var onPhysicalKeyReceived: ((Char) -> Unit)? = null
    private var onPhysicalBackspaceReceived: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shelfRangeDb = ShelfRangeDatabase(this)
        setupPrefs = ShelfSetupPreferences(this)
        repo = ShelfRepository(this, UsageRepository(this))

        // Crash logging handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("SetupActivity", "UNCAUGHT CRASH on thread ${thread.name}", throwable)
            try {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                logFile.writeText("Crash on ${java.util.Date()}:\n" + Log.getStackTraceString(throwable))
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        setContent {
            MaterialTheme {
                MainSetupContainer(
                    shelfRangeDb = shelfRangeDb,
                    setupPrefs = setupPrefs,
                    repo = repo,
                    onClose = { finish() },
                    registerKeyCallback = { onChar, onBksp ->
                        onPhysicalKeyReceived = onChar
                        onPhysicalBackspaceReceived = onBksp
                    }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    onPhysicalBackspaceReceived?.invoke()
                    return true
                }
                else -> {
                    val unicode = event.unicodeChar
                    if (unicode != 0 && !Character.isISOControl(unicode)) {
                        onPhysicalKeyReceived?.invoke(unicode.toChar())
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun MainSetupContainer(
    shelfRangeDb: ShelfRangeDatabase,
    setupPrefs: ShelfSetupPreferences,
    repo: ShelfRepository,
    onClose: () -> Unit,
    registerKeyCallback: ((Char) -> Unit, () -> Unit) -> Unit
) {
    var showLegacyOcr by remember { mutableStateOf(false) }

    if (showLegacyOcr) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E293B))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Vanha kuva/OCR -asetustila", color = Color.White, fontWeight = FontWeight.Bold)
                Button(
                    onClick = { showLegacyOcr = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Text("← Takaisin hyllyjen määritykseen", color = Color.White)
                }
            }
            SetupScreen(repo)
        }
    } else {
        RobotShelfSetupScreen(
            shelfRangeDb = shelfRangeDb,
            setupPrefs = setupPrefs,
            onClose = onClose,
            onOpenLegacyOcr = { showLegacyOcr = true },
            registerKeyCallback = registerKeyCallback
        )
    }
}

@Composable
fun RobotShelfSetupScreen(
    shelfRangeDb: ShelfRangeDatabase,
    setupPrefs: ShelfSetupPreferences,
    onClose: () -> Unit,
    onOpenLegacyOcr: () -> Unit,
    registerKeyCallback: ((Char) -> Unit, () -> Unit) -> Unit
) {
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("") }
    var shortcuts by remember { mutableStateOf(setupPrefs.getShortcuts()) }
    var showShelfListDialog by remember { mutableStateOf(false) }
    var newShortcutText by remember { mutableStateOf("") }
    var showAddShortcutDialog by remember { mutableStateOf(false) }
    var shortcutToDelete by remember { mutableStateOf<String?>(null) }
    var totalShelvesCount by remember { mutableStateOf(shelfRangeDb.list().size) }
    var preclasses by remember { mutableStateOf(setupPrefs.getPreclasses()) }
    var selectedPreclass by remember { mutableStateOf<String?>(null) }
    var preclassMenuExpanded by remember { mutableStateOf(false) }
    var showAddPreclassDialog by remember { mutableStateOf(false) }
    var newPreclassText by remember { mutableStateOf("") }

    // Live robot location state
    var robotX by remember { mutableStateOf<Float?>(null) }
    var robotY by remember { mutableStateOf<Float?>(null) }
    var robotYaw by remember { mutableStateOf<Float?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }

    fun refreshRobotPosition() {
        try {
            val robot = Robot.getInstance()
            val pos = robot.getPosition()
            robotX = pos.x
            robotY = pos.y
            robotYaw = pos.yaw
            locationError = null
        } catch (e: Throwable) {
            locationError = "Ei yhteyttä robottiin: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        refreshRobotPosition()
        totalShelvesCount = shelfRangeDb.list().size
    }

    // Register physical keyboard callback
    DisposableEffect(Unit) {
        registerKeyCallback(
            { char -> textInput += char },
            { if (textInput.isNotEmpty()) textInput = textInput.dropLast(1) }
        )
        onDispose { }
    }

    val normalized = remember(textInput) {
        textInput.trim().uppercase().replace('–', '-').replace('—', '-')
    }
    val parsedRange = remember(normalized) {
        ShelfRangeParser.parseRange(normalized)
    }
    val isValid = parsedRange != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // TOP BAR
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "HYLLYJEN MÄÄRITYS",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )

                    // Robot Position Badge
                    Surface(
                        color = if (robotX != null) Color(0xFF064E3B) else Color(0xFF334155),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { refreshRobotPosition() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (robotX != null) {
                                    "📍 X: ${String.format(Locale.US, "%.2f", robotX)} m | Y: ${String.format(Locale.US, "%.2f", robotY)} m | Yaw: ${String.format(Locale.US, "%.0f", robotYaw)}°"
                                } else {
                                    "📍 Ei robotin sijaintia (Päivitä)"
                                },
                                color = if (robotX != null) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Action Buttons (Shelf list, Close)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            totalShelvesCount = shelfRangeDb.list().size
                            showShelfListDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "📚 Hyllylista ($totalShelvesCount)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    TextButton(
                        onClick = onOpenLegacyOcr
                    ) {
                        Text("Kuvat/OCR", color = Color(0xFF64748B), fontSize = 12.sp)
                    }

                    Button(
                        onClick = onClose,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("✕ Sulje", color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // TEXT DISPLAY & INPUT AREA
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 2.dp,
                        color = when {
                            textInput.isBlank() -> Color(0xFF334155)
                            isValid -> Color(0xFF10B981)
                            else -> Color(0xFFF59E0B)
                        },
                        shape = RoundedCornerShape(10.dp)
                    ),
                color = Color(0xFF1E293B),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (textInput.isEmpty()) "Aloita kirjoittaminen..." else textInput,
                                color = if (textInput.isEmpty()) Color(0xFF64748B) else Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            // Blinking cursor
                            Text(
                                text = " |",
                                color = Color(0xFF38BDF8),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Light
                            )
                        }

                        // Validation guidance
                        if (textInput.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            if (isValid && parsedRange != null) {
                                val details = buildString {
                                    append("✓ Kelvollinen: Osasto ${parsedRange.section} | Luokka ${parsedRange.start.classNumber}")
                                    if (parsedRange.start.authorStart != null || parsedRange.end?.authorEnd != null) {
                                        val from = parsedRange.start.authorStart ?: "…"
                                        val to = parsedRange.end?.authorEnd ?: "…"
                                        append(" | Tekijät: $from – $to")
                                    }
                                }
                                Text(
                                    text = details,
                                    color = Color(0xFF34D399),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text = "ℹ Esimerkki: AIK84.2A-CAN, AIKMYC14-17 tai AIK81-82.2",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (textInput.isNotEmpty()) {
                            Button(
                                onClick = { textInput = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF475569)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("Tyhjennä", color = Color.White, fontSize = 13.sp)
                            }
                        }

                        // Prominent "Save Shelf at Robot Position" button
                        Button(
                            onClick = {
                                if (!isValid) {
                                    Toast.makeText(context, "Syötä kelvollinen hyllyväli ensin (esim. AIK84.2CON-D)", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                refreshRobotPosition()
                                val x = robotX ?: 0.0f
                                val y = robotY ?: 0.0f
                                val yaw = robotYaw ?: 0.0f

                                try {
                                    val saved = shelfRangeDb.upsert(
                                        text = normalized,
                                        x = x.toDouble(),
                                        y = y.toDouble(),
                                        yaw = yaw.toDouble(),
                                        preclass = selectedPreclass,
                                        setPreclass = true
                                    )
                                    totalShelvesCount = shelfRangeDb.list().size
                                    Toast.makeText(
                                        context,
                                        "Tallennettu hylly ${saved.text} sijaintiin (${String.format(Locale.US, "%.2f", x)}, ${String.format(Locale.US, "%.2f", y)})",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    textInput = ""
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Tallennusvirhe: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = isValid,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF059669),
                                disabledContainerColor = Color(0xFF1E3A2F)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "💾 Tallenna robotin sijaintiin",
                                color = if (isValid) Color.White else Color(0xFF6EE7B7).copy(alpha = 0.5f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Esiluokka:",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { preclassMenuExpanded = true },
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f))
                    ) {
                        Text(
                            text = selectedPreclass ?: "Ei esiluokkaa",
                            color = if (selectedPreclass == null) Color(0xFF94A3B8) else Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = preclassMenuExpanded,
                        onDismissRequest = { preclassMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Ei esiluokkaa") },
                            onClick = {
                                selectedPreclass = null
                                preclassMenuExpanded = false
                            }
                        )
                        preclasses.forEach { label ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedPreclass = label
                                    preclassMenuExpanded = false
                                }
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            newPreclassText = ""
                            showAddPreclassDialog = true
                        },
                    color = Color(0xFF0369A1),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // SHORTCUT BUTTONS ROW (Above the keyboard)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pikapainikkeet:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 2.dp)
                )

                shortcuts.forEach { shortcut ->
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .pointerInput(shortcut) {
                                detectTapGestures(
                                    onTap = { textInput += shortcut },
                                    onLongPress = { shortcutToDelete = shortcut }
                                )
                            },
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.4f))
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = shortcut,
                                color = Color(0xFF38BDF8),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Add shortcut button
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            newShortcutText = textInput.trim().uppercase()
                            showAddShortcutDialog = true
                        },
                    color = Color(0xFF0369A1),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "+ Lisää",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // FINNISH VIRTUAL KEYBOARD
            FinnishVirtualKeyboard(
                onKeyPress = { key -> textInput += key },
                onBackspace = {
                    if (textInput.isNotEmpty()) {
                        textInput = textInput.dropLast(1)
                    }
                },
                onClear = { textInput = "" },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- Dialog: Shelf List Management ---
        if (showShelfListDialog) {
            ShelfListDialog(
                database = shelfRangeDb,
                onDismiss = {
                    totalShelvesCount = shelfRangeDb.list().size
                    showShelfListDialog = false
                }
            )
        }

        // --- Dialog: Add esiluokka ---
        if (showAddPreclassDialog) {
            AlertDialog(
                onDismissRequest = { showAddPreclassDialog = false },
                title = { Text("Lisää esiluokka", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Esiluokka on Finna-hyllytiedon alkuosa, esim. Jännitys.",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                        OutlinedTextField(
                            value = newPreclassText,
                            onValueChange = { newPreclassText = it },
                            label = { Text("Esiluokka") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val cleaned = newPreclassText.trim()
                        if (cleaned.isNotBlank()) {
                            preclasses = setupPrefs.addPreclass(cleaned)
                            selectedPreclass = preclasses.firstOrNull {
                                it.equals(cleaned, ignoreCase = true)
                            } ?: cleaned
                            Toast.makeText(context, "Esiluokka '$cleaned' lisätty", Toast.LENGTH_SHORT).show()
                        }
                        showAddPreclassDialog = false
                    }) {
                        Text("Lisää")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPreclassDialog = false }) {
                        Text("Peruuta")
                    }
                }
            )
        }

        // --- Dialog: Add New Shortcut ---
        if (showAddShortcutDialog) {
            AlertDialog(
                onDismissRequest = { showAddShortcutDialog = false },
                title = { Text("Lisää pikapainike", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Pikapainike syöttää tekstin yhdellä painalluksella (esim. AIK, 84.2, LAP).", fontSize = 13.sp, color = Color.Gray)
                        OutlinedTextField(
                            value = newShortcutText,
                            onValueChange = { newShortcutText = it },
                            label = { Text("Teksti") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val cleaned = newShortcutText.trim().uppercase()
                        if (cleaned.isNotBlank()) {
                            shortcuts = setupPrefs.addShortcut(cleaned)
                            Toast.makeText(context, "Pikapainike '$cleaned' lisätty", Toast.LENGTH_SHORT).show()
                        }
                        showAddShortcutDialog = false
                    }) {
                        Text("Lisää")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddShortcutDialog = false }) {
                        Text("Peruuta")
                    }
                }
            )
        }

        // --- Dialog: Delete Shortcut Confirmation ---
        if (shortcutToDelete != null) {
            val target = shortcutToDelete!!
            AlertDialog(
                onDismissRequest = { shortcutToDelete = null },
                title = { Text("Poista pikapainike?", fontWeight = FontWeight.Bold) },
                text = { Text("Haluatko poistaa pikapainikkeen '$target'?") },
                confirmButton = {
                    Button(
                        onClick = {
                            shortcuts = setupPrefs.removeShortcut(target)
                            shortcutToDelete = null
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Text("Poista")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { shortcutToDelete = null }) {
                        Text("Peruuta")
                    }
                }
            )
        }
    }
}

// -------------------------------------------------------------
// Legacy Photo & OCR Prototype Screen (Retained for compatibility)
// -------------------------------------------------------------

@Composable
fun SetupScreen(repo: ShelfRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detected by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var acceptedIds by remember { mutableStateOf(setOf<String>()) }
    var draftOnly by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        detected = repo.listShelves().filter { if (draftOnly) !it.active else true }
    }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)) {

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val zip = java.io.File(downloads, "kuvat.zip")
                if (!zip.exists()) {
                    Toast.makeText(repo.context, "kuvat.zip not found in Downloads", Toast.LENGTH_LONG).show()
                    return@Button
                }
                busy = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val imported = repo.importZip(zip.absolutePath)
                        val analyzed = repo.analyzeImagesReturnNew(imported)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            detected = analyzed
                            acceptedIds = emptySet()
                            busy = false
                        }
                    } catch (e: Throwable) {
                        repo.reportFailure("Setup ZIP import", e)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            busy = false
                            Toast.makeText(repo.context, "Error importing ZIP: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) {
                Text("Import ZIP from Downloads (kuvat.zip)")
            }

            Button(onClick = {
                busy = true
                scope.launch(Dispatchers.IO) {
                    try {
                        val imagesDir = java.io.File(repo.context.filesDir, "shelf_images")
                        val images = imagesDir.listFiles()?.map { it.absolutePath } ?: emptyList()
                        if (images.isEmpty()) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                busy = false
                                Toast.makeText(repo.context, "No images found in shelf_images. Please import kuvat.zip first.", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                        val analyzed = repo.analyzeImagesReturnNew(images)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            detected = analyzed
                            acceptedIds = emptySet()
                            busy = false
                            Toast.makeText(repo.context, "Analyzed ${analyzed.size} image(s).", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Throwable) {
                        repo.reportFailure("Setup image analysis", e)
                        val cause = e.cause ?: e
                        val detail = "${cause.javaClass.simpleName}: ${cause.message ?: e.message ?: "Unknown error"}"
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            busy = false
                            Toast.makeText(repo.context, "Error analyzing images: $detail", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) {
                Text("Analyze images (OCR)")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val toSave = detected.filter { acceptedIds.contains(it.id) }
                        if (toSave.isNotEmpty()) {
                            val saved = toSave.map { draft ->
                                val updated = draft.copy(active = false, lastUpdated = System.currentTimeMillis())
                                repo.upsertShelf(updated)
                                updated
                            }
                            val invalid = saved.filter { !it.isReadyForActivation() }
                            val reloaded = repo.listShelves().filter { if (draftOnly) !it.active else true }
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                if (invalid.isNotEmpty()) {
                                    Toast.makeText(repo.context, "Some shelves are incomplete; save as draft or capture Temi position first", Toast.LENGTH_LONG).show()
                                }
                                detected = reloaded
                                acceptedIds = emptySet()
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e("SetupActivity", "Error saving drafts", e)
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            Toast.makeText(repo.context, "Error saving drafts: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) {
                Text("Save accepted shelves as draft")
            }

            Button(onClick = {
                AlertDialog.Builder(context as android.app.Activity)
                    .setTitle("Cancel setup?")
                    .setMessage("This will close Setup Mode without saving current changes.")
                    .setPositiveButton("Cancel setup") { _, _ ->
                        (context as android.app.Activity).finish()
                    }
                    .setNegativeButton("Stay", null)
                    .show()
            }) {
                Text("Cancel")
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(onClick = {
            draftOnly = !draftOnly
            detected = repo.listShelves().filter { if (draftOnly) !it.active else true }
        }) {
            Text(if (draftOnly) "Show active shelves" else "Show draft shelves")
        }

        Spacer(Modifier.height(12.dp))

        val statusTitle = if (draftOnly) "Draft shelves (${detected.size})" else "Detected shelves (${detected.size})"
        Text(statusTitle, style = MaterialTheme.typography.titleMedium)

        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            items(items = detected, key = { it.id }) { s ->
                EditableShelfRow(s, repo, acceptedIds, onAcceptedChange = { newAccepted ->
                    acceptedIds = newAccepted
                }, onEdit = { updatedShelf ->
                    val list = detected.toMutableList()
                    val idx = list.indexOfFirst { it.id == updatedShelf.id }
                    if (idx >= 0) {
                        list[idx] = updatedShelf
                        detected = list
                    }
                })
            }
        }

        if (busy) {
            Text("Working...", modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
fun EditableShelfRow(original: Shelf, repo: ShelfRepository, acceptedIds: Set<String>, onAcceptedChange: (Set<String>) -> Unit, onEdit: (Shelf) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var section by remember(original.id) { mutableStateOf(original.section ?: "") }
    var rangeStart by remember(original.id) { mutableStateOf(original.rangeStart ?: "") }
    var rangeEnd by remember(original.id) { mutableStateOf(original.rangeEnd ?: "") }
    var ocrText by remember(original.id) { mutableStateOf(original.rawText ?: "") }
    var accepted by remember(original.id, acceptedIds) { mutableStateOf(acceptedIds.contains(original.id)) }
    var currentSuggestion by remember(original.id) { mutableStateOf(original.ocrSuggestion) }
    var confidenceScore by remember(original.id) { mutableStateOf(original.confidence) }

    val isLowConfidence = confidenceScore < 0.85 || original.requiresVerification || currentSuggestion != null

    androidx.compose.material3.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = if (isLowConfidence) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)) {

            val formattedConfidence = "${(confidenceScore * 100).toInt()}%"

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${original.id} — Confidence: $formattedConfidence",
                    style = MaterialTheme.typography.titleMedium
                )
                androidx.compose.material3.Checkbox(checked = accepted, onCheckedChange = { checked ->
                    accepted = checked
                    val newSet = acceptedIds.toMutableSet()
                    if (checked) newSet.add(original.id) else newSet.remove(original.id)
                    onAcceptedChange(newSet)
                })
            }

            if (isLowConfidence) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "⚠ Low confidence detection ($formattedConfidence)",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "Detected raw text: \"${ocrText.ifBlank { original.normalizedText ?: "(none)" }}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (!currentSuggestion.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Did you mean: ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = {
                                        val parts = LibraryShelfOcr.parseRangeToken(currentSuggestion!!)
                                        if (parts != null) {
                                            rangeStart = parts.first ?: rangeStart
                                            rangeEnd = parts.second ?: rangeEnd
                                            confidenceScore = 0.95
                                            currentSuggestion = null
                                            Toast.makeText(context, "Applied suggestion: ${parts.first}-${parts.second}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text("[ $currentSuggestion ]")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text("Image: ${original.imagePath ?: "(none)"}", style = MaterialTheme.typography.bodySmall)

            if (original.boundingBox != null) {
                val bb = original.boundingBox
                Text(
                    text = "Sign Region (Bounding Box): x=${bb.x}, y=${bb.y}, size=${bb.width}x${bb.height}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (!original.draftNotes.isNullOrBlank()) {
                Text("Detected hints: ${original.draftNotes}", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.OutlinedTextField(
                    value = section,
                    onValueChange = { section = it },
                    label = { Text("Section") },
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = rangeStart,
                    onValueChange = { rangeStart = it },
                    label = { Text("Range start (e.g. B, V)") },
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.OutlinedTextField(
                    value = rangeEnd,
                    onValueChange = { rangeEnd = it },
                    label = { Text("Range end (e.g. C, Ö)") },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text("Raw OCR Output: ", style = MaterialTheme.typography.labelSmall)
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = ocrText.ifBlank { original.normalizedText ?: "(no OCR text)" },
                    maxLines = 4,
                    modifier = Modifier.padding(6.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = {
                    try {
                        val file = java.io.File(original.imagePath ?: "")
                        if (file.exists()) {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "image/*")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        } else {
                            Toast.makeText(context, "Image file not found", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Cannot open image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Open Image")
                }

                Button(onClick = {
                    val updatedShelf = original.copy(
                        section = section.ifBlank { null },
                        rangeStart = rangeStart.ifBlank { null },
                        rangeEnd = rangeEnd.ifBlank { null },
                        confidence = confidenceScore,
                        requiresVerification = false,
                        lastUpdated = System.currentTimeMillis()
                    )
                    onEdit(updatedShelf)
                    Toast.makeText(context, "Edits applied locally for ${original.id}", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Apply Edits")
                }

                Button(onClick = {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val robot = Robot.getInstance()
                            val pos = robot.getPosition()
                            val updatedShelf = original.copy(
                                section = section.ifBlank { null },
                                rangeStart = rangeStart.ifBlank { null },
                                rangeEnd = rangeEnd.ifBlank { null },
                                mapX = pos.x.toDouble(),
                                mapY = pos.y.toDouble(),
                                yaw = pos.yaw.toDouble(),
                                lastUpdated = System.currentTimeMillis(),
                                active = false
                            )
                            repo.updateShelfLocation(updatedShelf.id, updatedShelf.mapX ?: 0.0, updatedShelf.mapY ?: 0.0, updatedShelf.yaw ?: 0.0)
                            val persisted = repo.listShelves().firstOrNull { it.id == updatedShelf.id } ?: updatedShelf
                            onEdit(persisted)
                            (context as android.app.Activity).runOnUiThread {
                                Toast.makeText(context, "Temi position saved (${pos.x}, ${pos.y})", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            (context as android.app.Activity).runOnUiThread {
                                Toast.makeText(context, "Could not read Temi position: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) {
                    Text("Capture Position")
                }

                Button(onClick = {
                    val updated = original.copy(
                        section = section.ifBlank { null },
                        rangeStart = rangeStart.ifBlank { null },
                        rangeEnd = rangeEnd.ifBlank { null },
                        active = false,
                        lastUpdated = System.currentTimeMillis()
                    )

                    val missing = buildList {
                        if (updated.section.isNullOrBlank()) add("section")
                        if (updated.rangeStart.isNullOrBlank() && updated.rangeEnd.isNullOrBlank()) add("range")
                        if (updated.mapX == null || updated.mapY == null || updated.yaw == null) add("Temi position")
                        if (updated.imagePath.isNullOrBlank()) add("image")
                    }

                    val confirmMessage = if (missing.isEmpty()) {
                        "Approve this shelf and activate it for Temi navigation?"
                    } else {
                        "This shelf is incomplete: ${missing.joinToString(", ")}. Save as draft?"
                    }

                    AlertDialog.Builder(context as android.app.Activity)
                        .setTitle("Human Verification")
                        .setMessage(confirmMessage)
                        .setPositiveButton("Confirm & Save") { _, _ ->
                            scope.launch(Dispatchers.IO) {
                                val finalShelf = updated.copy(
                                    active = missing.isEmpty(),
                                    requiresVerification = false
                                )
                                repo.upsertShelf(finalShelf)
                                if (missing.isEmpty()) {
                                    val activated = repo.activateShelf(finalShelf.id)
                                    (context as android.app.Activity).runOnUiThread {
                                        Toast.makeText(context, if (activated) "Shelf ${finalShelf.id} activated!" else "Could not activate.", Toast.LENGTH_LONG).show()
                                    }
                                } else {
                                    (context as android.app.Activity).runOnUiThread {
                                        Toast.makeText(context, "Shelf ${finalShelf.id} saved as draft.", Toast.LENGTH_LONG).show()
                                    }
                                }
                                onEdit(finalShelf)
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }) {
                    Text("Approve & Save")
                }
            }
        }
    }
}
