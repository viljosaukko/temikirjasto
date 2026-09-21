package com.kirjasto.kirjastobotti

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.robotemi.sdk.Robot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/** Manual shelf setup. Images and OCR are deliberately not used. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { ManualShelfSetup(ShelfRepository(this, UsageRepository(this))) }
        }
    }
}

@Composable
private fun ManualShelfSetup(repo: ShelfRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var shelves by remember { mutableStateOf(repo.listShelves()) }
    var id by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("") }
    var rangeStart by remember { mutableStateOf("") }
    var rangeEnd by remember { mutableStateOf("") }
    var contents by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var savedPosition by remember { mutableStateOf<Triple<Double, Double, Double>?>(null) }

    fun clearForm() {
        id = ""; section = ""; rangeStart = ""; rangeEnd = ""; contents = ""; notes = ""
        savedPosition = null
    }

    fun save(capturePosition: Boolean) {
        val shelfId = id.trim()
        if (shelfId.isBlank() || section.isBlank() || (rangeStart.isBlank() && rangeEnd.isBlank() && contents.isBlank())) {
            Toast.makeText(context, "Täytä hyllytunniste, osasto sekä luokitusväli tai sisällön kuvaus.", Toast.LENGTH_LONG).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val position = if (capturePosition) {
                    Robot.getInstance().getPosition().let { Triple(it.x.toDouble(), it.y.toDouble(), it.yaw.toDouble()) }
                } else savedPosition
                val shelf = Shelf(
                    id = shelfId,
                    section = section.trim(),
                    rangeStart = rangeStart.trim().ifBlank { null },
                    rangeEnd = rangeEnd.trim().ifBlank { null },
                    contents = contents.trim().ifBlank { null },
                    mapX = position?.first,
                    mapY = position?.second,
                    yaw = position?.third,
                    active = position != null,
                    draftNotes = notes.trim().ifBlank { null }
                )
                repo.upsertShelf(shelf)
                shelves = repo.listShelves()
                savedPosition = position
                (context as android.app.Activity).runOnUiThread {
                    Toast.makeText(context, if (shelf.active) "Hylly tallennettu ja aktivoitu." else "Hylly tallennettu luonnoksena – tallenna Temin sijainti hyllyn kohdalla.", Toast.LENGTH_LONG).show()
                }
            } catch (error: Throwable) {
                repo.reportFailure("Manual shelf setup", error)
                (context as android.app.Activity).runOnUiThread {
                    Toast.makeText(context, "Sijaintia ei voitu tallentaa: ${error.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Manuaalinen hyllyjen keruu", style = MaterialTheme.typography.headlineSmall)
        Text("Täytä tiedot hyllyn kohdalla ja tallenna Temin sijainti. OCR:ää tai kuvia ei käytetä.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(id, { id = it }, label = { Text("Hyllytunniste (esim. KAUNO-A1)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(section, { section = it }, label = { Text("Osasto / alue") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(rangeStart, { rangeStart = it }, label = { Text("Luokitusvälin alku") }, modifier = Modifier.weight(1f))
            OutlinedTextField(rangeEnd, { rangeEnd = it }, label = { Text("Luokitusvälin loppu") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(contents, { contents = it }, label = { Text("Hyllyn sisältö / hakusanat") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(notes, { notes = it }, label = { Text("Lisätiedot, kerros, käytävä, suunta") }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            Button(onClick = { save(false) }) { Text("Tallenna luonnos") }
            Button(onClick = { save(true) }) { Text("Tallenna Temin sijainti") }
            Button(onClick = { clearForm() }) { Text("Uusi hylly") }
        }
        Text("Tallennetut hyllyt (${shelves.size})", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.weight(1f)) {
            items(shelves, key = { it.id }) { shelf ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(shelf.id, style = MaterialTheme.typography.titleMedium)
                        Text("${shelf.section ?: ""} · ${shelf.rangeStart ?: ""}${if (shelf.rangeEnd.isNullOrBlank()) "" else " – ${shelf.rangeEnd}"}")
                        if (!shelf.contents.isNullOrBlank()) Text(shelf.contents)
                        if (!shelf.draftNotes.isNullOrBlank()) Text(shelf.draftNotes, style = MaterialTheme.typography.bodySmall)
                        Text(if (shelf.active) "Temin sijainti tallennettu" else "Luonnos: Temin sijainti puuttuu", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            id = shelf.id; section = shelf.section.orEmpty(); rangeStart = shelf.rangeStart.orEmpty(); rangeEnd = shelf.rangeEnd.orEmpty()
                            contents = shelf.contents.orEmpty(); notes = shelf.draftNotes.orEmpty()
                            savedPosition = shelf.mapX?.let { x -> shelf.mapY?.let { y -> shelf.yaw?.let { yaw -> Triple(x, y, yaw) } } }
                        }) { Text("Muokkaa") }
                    }
                }
            }
        }
    }
}
