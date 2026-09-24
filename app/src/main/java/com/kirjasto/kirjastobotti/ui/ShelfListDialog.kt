package com.kirjasto.kirjastobotti.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kirjasto.kirjastobotti.ShelfRange
import com.kirjasto.kirjastobotti.ShelfRangeDatabase
import com.kirjasto.kirjastobotti.ShelfRangeParser
import com.robotemi.sdk.Robot
import java.util.Locale

/**
 * Dialog displaying all registered shelves, allowing staff to:
 * - Delete shelves
 * - Change the name and preclass without modifying positioning
 * - Change coordinates manually or by capturing robot's current position
 */
@Composable
fun ShelfListDialog(
    database: ShelfRangeDatabase,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shelves by remember { mutableStateOf(database.list()) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog state for renaming & preclass
    var renamingShelf by remember { mutableStateOf<ShelfRange?>(null) }
    var newShelfName by remember { mutableStateOf("") }
    var editingPreclass by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }

    // Dialog state for coordinate editing
    var editingCoordsShelf by remember { mutableStateOf<ShelfRange?>(null) }
    var inputX by remember { mutableStateOf("") }
    var inputY by remember { mutableStateOf("") }
    var inputYaw by remember { mutableStateOf("") }
    var coordError by remember { mutableStateOf<String?>(null) }

    // Dialog state for delete confirmation
    var deletingShelf by remember { mutableStateOf<ShelfRange?>(null) }

    fun refreshList() {
        shelves = database.list()
    }

    val filteredShelves = remember(shelves, searchQuery) {
        if (searchQuery.isBlank()) shelves
        else shelves.filter { it.text.contains(searchQuery.trim(), ignoreCase = true) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F172A),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Hyllylista (Määritetyt hyllyt)",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Yhteensä ${shelves.size} hyllyväliä määritetty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155))
                        ) {
                            Text("✕ Sulje", color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Etsi hyllyä (esim. AIK tai 84.2)", color = Color(0xFF94A3B8)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF475569)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Shelves List
                if (filteredShelves.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "Ei vielä määritettyjä hyllyjä." else "Ei hakua vastaavia hyllyjä.",
                            color = Color(0xFF64748B),
                            fontSize = 16.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredShelves, key = { it.id }) { shelf ->
                            ShelfItemCard(
                                shelf = shelf,
                                onEditName = {
                                    renamingShelf = shelf
                                    newShelfName = shelf.text
                                    editingPreclass = shelf.normalizedPreclass.orEmpty()
                                    renameError = null
                                },
                                onEditCoordinates = {
                                    editingCoordsShelf = shelf
                                    inputX = String.format(Locale.US, "%.2f", shelf.mapX ?: 0.0)
                                    inputY = String.format(Locale.US, "%.2f", shelf.mapY ?: 0.0)
                                    inputYaw = String.format(Locale.US, "%.1f", shelf.yaw ?: 0.0)
                                    coordError = null
                                },
                                onDelete = {
                                    deletingShelf = shelf
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // --- Sub-Dialog: Edit Shelf Name & Preclass ---
    if (renamingShelf != null) {
        val target = renamingShelf!!
        AlertDialog(
            onDismissRequest = { renamingShelf = null },
            title = { Text("Muokkaa hyllyä", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Nykyinen nimi: ${target.text}\nSijainti säilyy muuttumattomana.",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = newShelfName,
                        onValueChange = {
                            newShelfName = it
                            renameError = null
                        },
                        label = { Text("Hyllynimi (esim. AIK84.2A-CAN, AIKMYC14-17)") },
                        singleLine = true,
                        isError = renameError != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (renameError != null) {
                        Text(renameError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = editingPreclass,
                        onValueChange = { editingPreclass = it },
                        label = { Text("Tagit (pilkulla erotettuna)") },
                        placeholder = { Text("Tarkka, Jännitys") },
                        supportingText = { Text("Voit liittää hyllyyn useita tageja.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val normalized = newShelfName.trim().uppercase().replace('–', '-').replace('—', '-')
                    if (ShelfRangeParser.parseRange(normalized) == null) {
                        renameError = "Virheellinen hyllymuoto. Esimerkki: AIK84.2CON-D tai AIKMYC14-17"
                        return@Button
                    }
                    try {
                        database.updateShelf(target.id, normalized, editingPreclass, setPreclass = true)
                        Toast.makeText(context, "Hylly päivitetty: $normalized", Toast.LENGTH_SHORT).show()
                        refreshList()
                        renamingShelf = null
                    } catch (e: Exception) {
                        renameError = e.message ?: "Tallennus epäonnistui"
                    }
                }) {
                    Text("Tallenna")
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingShelf = null }) {
                    Text("Peruuta")
                }
            }
        )
    }

    // --- Sub-Dialog: Edit Coordinates ---
    if (editingCoordsShelf != null) {
        val target = editingCoordsShelf!!
        AlertDialog(
            onDismissRequest = { editingCoordsShelf = null },
            title = { Text("Muuta koordinaatteja: ${target.text}", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Voit syöttää koordinaatit käsin tai asettaa ne robotin tämänhetkiseen sijaintiin.",
                        fontSize = 14.sp,
                        color = Color.Gray
                    )

                    Button(
                        onClick = {
                            try {
                                val pos = Robot.getInstance().getPosition()
                                inputX = String.format(Locale.US, "%.2f", pos.x)
                                inputY = String.format(Locale.US, "%.2f", pos.y)
                                inputYaw = String.format(Locale.US, "%.1f", pos.yaw)
                                Toast.makeText(context, "Robotin sijainti haettu!", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                coordError = "Ei saatu robotin sijaintia: ${e.message}"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📍 Käytä robotin nykyistä sijaintia", color = Color.White)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputX,
                            onValueChange = { inputX = it; coordError = null },
                            label = { Text("X (m)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = inputY,
                            onValueChange = { inputY = it; coordError = null },
                            label = { Text("Y (m)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = inputYaw,
                            onValueChange = { inputYaw = it; coordError = null },
                            label = { Text("Yaw (°)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (coordError != null) {
                        Text(coordError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val x = inputX.toDoubleOrNull()
                    val y = inputY.toDoubleOrNull()
                    val yaw = inputYaw.toDoubleOrNull()

                    if (x == null || y == null || yaw == null) {
                        coordError = "Syötä kelvolliset numerot koordinaateille (X, Y, Yaw)."
                        return@Button
                    }

                    try {
                        database.updateCoordinates(target.id, x, y, yaw)
                        Toast.makeText(context, "Koordinaatit päivitetty!", Toast.LENGTH_SHORT).show()
                        refreshList()
                        editingCoordsShelf = null
                    } catch (e: Exception) {
                        coordError = e.message ?: "Tallennus epäonnistui"
                    }
                }) {
                    Text("Tallenna koordinaatit")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingCoordsShelf = null }) {
                    Text("Peruuta")
                }
            }
        )
    }

    // --- Sub-Dialog: Delete Confirmation ---
    if (deletingShelf != null) {
        val target = deletingShelf!!
        AlertDialog(
            onDismissRequest = { deletingShelf = null },
            title = { Text("Poista hylly?", fontWeight = FontWeight.Bold) },
            text = {
                Text("Haluatko varmasti poistaa hyllyn ${target.text}? Tätä toimintoa ei voi kumota.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        database.delete(target.id)
                        Toast.makeText(context, "Hylly ${target.text} poistettu", Toast.LENGTH_SHORT).show()
                        refreshList()
                        deletingShelf = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("Poista hylly", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingShelf = null }) {
                    Text("Peruuta")
                }
            }
        )
    }
}

@Composable
private fun ShelfItemCard(
    shelf: ShelfRange,
    onEditName: () -> Unit,
    onEditCoordinates: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shelf.text,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                val preclassLabel = shelf.normalizedPreclass
                if (preclassLabel != null) {
                    Text(
                        text = "Tagit: $preclassLabel",
                        color = Color(0xFFA78BFA),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (shelf.hasLocation) {
                    Text(
                        text = "📍 X: ${String.format(Locale.US, "%.2f", shelf.mapX)} m | Y: ${String.format(Locale.US, "%.2f", shelf.mapY)} m | Yaw: ${String.format(Locale.US, "%.1f", shelf.yaw)}°",
                        color = Color(0xFF38BDF8),
                        fontSize = 13.sp
                    )
                } else {
                    Text(
                        text = "⚠ Ei sijaintia määritetty",
                        color = Color(0xFFFBBF24),
                        fontSize = 13.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEditName,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("✎ Muokkaa", fontSize = 13.sp, color = Color.White)
                }

                Button(
                    onClick = onEditCoordinates,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📍 Koordinaatit", fontSize = 13.sp, color = Color.White)
                }

                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF991B1B)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("🗑 Poista", fontSize = 13.sp, color = Color.White)
                }
            }
        }
    }
}
