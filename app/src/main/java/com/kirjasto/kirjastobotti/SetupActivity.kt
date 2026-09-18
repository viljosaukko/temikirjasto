package com.kirjasto.kirjastobotti

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Simple SetupActivity that imports images from a ZIP in Downloads and runs a lightweight analysis
 * to produce shelf metadata, which is stored in a JSON file.
 *
 * This is a prototype implementation of the "Setup Mode" described in the shared design.
 */
class SetupActivity : ComponentActivity() {

    private lateinit var repo: ShelfRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ShelfRepository(this)

        // Install uncaught exception handler to prevent silent crash exits and capture stack trace
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
                SetupScreen(repo)
            }
        }
    }
}

@Composable
fun SetupScreen(repo: ShelfRepository) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var detected by remember { mutableStateOf<List<Shelf>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    // Track which shelves user has accepted for saving
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
                        Log.e("SetupActivity", "Error importing ZIP", e)
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
                        Log.e("SetupActivity", "Error analyzing images", e)
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
    val context = androidx.compose.ui.platform.LocalContext.current

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

            // Low Confidence Warning Alert Box
            if (isLowConfidence) {
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
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
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
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
                            val robot = com.robotemi.sdk.Robot.getInstance()
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
