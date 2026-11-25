package com.retrohandheld.emulator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C4DFF),
                    background = Color(0xFF121218),
                    surface = Color(0xFF1E1E2A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val nativeDir = context.applicationInfo.nativeLibraryDir
    var pendingSystem by remember { mutableStateOf<ConsoleSystem?>(null) }

    val romPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val system = pendingSystem ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult

        val copiedFile = copyUriToCache(context, uri)
        if (copiedFile == null) {
            Toast.makeText(context, "Could not read ROM file.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }

        val coreFile = system.findInstalledCore(nativeDir)
        if (coreFile == null) {
            Toast.makeText(
                context,
                "Missing core for ${system.shortName}. See README.md to add ${system.coreCandidates.first()}.",
                Toast.LENGTH_LONG
            ).show()
            return@rememberLauncherForActivityResult
        }

        val intent = Intent(context, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_CORE_PATH, coreFile.absolutePath)
            putExtra(EmulatorActivity.EXTRA_ROM_PATH, copiedFile.absolutePath)
            putExtra(EmulatorActivity.EXTRA_SYSTEM_NAME, system.displayName)
            putExtra(EmulatorActivity.EXTRA_IS_DUAL_SCREEN, system.isDualScreen)
        }
        context.startActivity(intent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Text(
            text = "RetroHandheld",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Select a console and pick a ROM file to play.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.LightGray
        )
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(ConsoleSystem.entries) { system ->
                val coreReady = system.findInstalledCore(nativeDir) != null
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = system.displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Extensions: ${system.extensions.joinToString(", ") { ".$it" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Text(
                                text = if (coreReady) "Core installed" else "Core missing (${system.coreCandidates.first()})",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (coreReady) Color(0xFF69F0AE) else Color(0xFFFFB74D)
                            )
                        }
                        Button(
                            onClick = {
                                pendingSystem = system
                                romPicker.launch(arrayOf("*/*"))
                            }
                        ) {
                            Text("Load ROM")
                        }
                    }
                }
            }
        }
    }
}

private fun copyUriToCache(context: android.content.Context, uri: Uri): File? {
    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
    } ?: "game.rom"

    val outDir = File(context.cacheDir, "roms").apply { mkdirs() }
    val outFile = File(outDir, name)
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
        outFile
    } catch (_: Exception) {
        null
    }
}
