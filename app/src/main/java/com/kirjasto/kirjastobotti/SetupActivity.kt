package com.kirjasto.kirjastobotti

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
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
                    val imported = repo.importZip(zip.absolutePath)
                    val analyzed = repo.analyzeImagesReturnNew(imported)
                    detected = analyzed
                    acceptedIds = emptySet()
                    busy = false
                }
            }) {
                Text("Import ZIP from Downloads (kuvat.zip)")
            }

            Button(onClick = {
                busy = true
                scope.launch(Dispatchers.IO) {
                    val imagesDir = java.io.File(repo.context.filesDir, "shelf_images")
                    val images = imagesDir.listFiles()?.map { it.absolutePath } ?: emptyList()
                    val analyzed = repo.analyzeImagesReturnNew(images)
                    detected = analyzed
                    acceptedIds = emptySet()
                    busy = false
                }
            }) {
                Text("Analyze images (OCR)")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    val toSave = detected.filter { acceptedIds.contains(it.id) }
                    if (toSave.isNotEmpty()) {
                        val saved = toSave.map { draft ->
                            val updated = draft.copy(active = false, lastUpdated = System.currentTimeMillis())
                            repo.upsertShelf(updated)
                            updated
                        }
                        val invalid = saved.filter { !it.isReadyForActivation() }
                        if (invalid.isNotEmpty()) {
                            (repo.context as android.app.Activity).runOnUiThread {
                                Toast.makeText(repo.context, "Some shelves are incomplete; save as draft or capture Temi position first", Toast.LENGTH_LONG).show()
                            }
                        }
                        detected = repo.listShelves().filter { if (draftOnly) !it.active else true }
                        acceptedIds = emptySet()
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
                items(detected) { s ->
                    EditableShelfRow(s, repo, acceptedIds, onAcceptedChange = { newAccepted ->
                    acceptedIds = newAccepted
                    }, onEdit = { updatedShelf ->
                        // replace in detected list
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

    var section by remember { mutableStateOf(original.section ?: "") }
    var rangeStart by remember { mutableStateOf(original.rangeStart ?: "") }
    var rangeEnd by remember { mutableStateOf(original.rangeEnd ?: "") }
    var ocrText by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(acceptedIds.contains(original.id)) }

    // Try to load OCR text in background when row first appears
    LaunchedEffect(original.id) {
        try {
            val file = java.io.File(original.imagePath ?: "")
            if (file.exists()) {
                ocrText = ImageOcr.recognizeTextBlocking(repo.context, file)
            }
        } catch (_: Exception) {
        }
    }

    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${original.id} — ${original.confidence}")
            androidx.compose.material3.Checkbox(checked = accepted, onCheckedChange = { checked ->
                accepted = checked
                val newSet = acceptedIds.toMutableSet()
                if (checked) newSet.add(original.id) else newSet.remove(original.id)
                onAcceptedChange(newSet)
            })
        }

        Spacer(Modifier.height(6.dp))

        Text("Image: ${original.imagePath ?: "(none)"}")
        if (!original.draftNotes.isNullOrBlank()) {
            Text("Detected hints: ${original.draftNotes}")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            androidx.compose.material3.OutlinedTextField(value = section, onValueChange = { section = it }, label = { Text("Section") }, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedTextField(value = rangeStart, onValueChange = { rangeStart = it }, label = { Text("Range start") }, modifier = Modifier.weight(1f))
            androidx.compose.material3.OutlinedTextField(value = rangeEnd, onValueChange = { rangeEnd = it }, label = { Text("Range end") }, modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.height(6.dp))

        Text("OCR: ")
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Text(ocrText.ifBlank { "(no OCR text)" }, maxLines = 6)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Open image using FileProvider for safety
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
                        Toast.makeText(context, "Image not found", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Cannot open image: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Open image")
            }

            Text(
                if (original.active) "Active" else "Draft",
                style = MaterialTheme.typography.labelMedium
            )

            Button(onClick = {
                val updatedShelf = original.copy(
                    section = section.ifBlank { null },
                    rangeStart = rangeStart.ifBlank { null },
                    rangeEnd = rangeEnd.ifBlank { null },
                    lastUpdated = System.currentTimeMillis()
                )
                onEdit(updatedShelf)
                (context as android.app.Activity).runOnUiThread {
                    Toast.makeText(context, "Edits applied locally for ${original.id}", Toast.LENGTH_SHORT).show()
                }
            }) {
                Text("Apply edits locally")
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
                            Toast.makeText(context, "Temi position saved for ${original.id} (${pos.x}, ${pos.y}, ${pos.yaw})", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        (context as android.app.Activity).runOnUiThread {
                            Toast.makeText(context, "Could not read Temi position: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }) {
                Text("Capture Temi position")
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
                    "Approve this shelf and activate it for robot navigation?"
                } else {
                    "This shelf is incomplete: ${missing.joinToString(", ")}. Save it as draft and review again?"
                }

                AlertDialog.Builder(context as android.app.Activity)
                    .setTitle("Final approval")
                    .setMessage(confirmMessage)
                    .setPositiveButton("Yes") { _, _ ->
                        scope.launch(Dispatchers.IO) {
                            val finalShelf = updated.copy(active = missing.isEmpty())
                            repo.upsertShelf(finalShelf)
                            if (missing.isEmpty()) {
                                val activated = repo.activateShelf(finalShelf.id)
                                (context as android.app.Activity).runOnUiThread {
                                    Toast.makeText(context, if (activated) "Shelf ${finalShelf.id} is now active." else "Shelf ${finalShelf.id} could not be activated.", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                (context as android.app.Activity).runOnUiThread {
                                    Toast.makeText(context, "Shelf ${finalShelf.id} saved as draft; missing: ${missing.joinToString(", ")}", Toast.LENGTH_LONG).show()
                                }
                            }
                            onEdit(finalShelf)
                        }
                    }
                    .setNegativeButton("No", null)
                    .show()
            }) {
                Text("Approve & activate")
            }
        }
    }
}
