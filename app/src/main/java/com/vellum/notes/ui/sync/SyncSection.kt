package com.vellum.notes.ui.sync

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.BackupManager
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.data.RoomNotesRepository
import com.vellum.notes.data.SyncCrypto
import com.vellum.notes.data.SyncFolder
import com.vellum.notes.data.SyncRepository
import com.vellum.notes.data.SyncSnapshot
import com.vellum.notes.data.db.AppDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val syncDateFormat = SimpleDateFormat("MM/dd/yy, h:mm a", Locale.US)

/**
 * Device-sync settings (Syncthing folder): pick the mirrored folder, export
 * snapshots, and import other devices' snapshots with an explicit confirm +
 * restart. The passphrase is asked at use time and never stored.
 */
@Composable
fun SyncSection(
    syncRepository: SyncRepository,
    notesRepository: NotesRepository,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dirUri by syncRepository.syncDirUri.collectAsState(initial = null)
    val notebooks by notesRepository.notebooks.collectAsState(initial = emptyList())

    var ownId by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf<List<SyncSnapshot.SnapshotMeta>>(emptyList()) }
    // fileName → document uri for imports.
    var snapshotUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    var passDialogForExport by remember { mutableStateOf(false) }
    var importTarget by remember { mutableStateOf<SyncSnapshot.SnapshotMeta?>(null) }
    var importPassOpen by remember { mutableStateOf(false) }
    var importDone by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    fun refreshSnapshots() {
        scope.launch {
            val id = syncRepository.resolveDeviceId()
            ownId = id
            val dir = dirUri
            if (dir == null) {
                snapshots = emptyList()
                snapshotUris = emptyMap()
                return@launch
            }
            val (metas, uris) = withContext(Dispatchers.IO) {
                val treeUri = runCatching { Uri.parse(dir) }.getOrNull()
                    ?: return@withContext emptyList<SyncSnapshot.SnapshotMeta>() to emptyMap()
                val files = SyncFolder.listFiles(context.contentResolver, treeUri)
                val all = SyncFolder.collectSnapshots(files) { doc ->
                    SyncFolder.readBytes(context.contentResolver, doc.uri)
                }
                val inbound = SyncSnapshot.inboundSnapshots(all, id)
                val uriMap = files
                    .filter { f -> inbound.any { it.fileName == f.name } }
                    .associate { it.name to it.uri }
                inbound to uriMap
            }
            snapshots = metas
            snapshotUris = uris
        }
    }

    LaunchedEffect(dirUri) { refreshSnapshots() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (t: Throwable) {
            status = "Folder permission was not granted."
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            syncRepository.setSyncDir(uri.toString())
            status = "Sync folder set — mirror it with Syncthing on your devices."
        }
    }

    val exportSnapshot: (CharArray?) -> Unit = { pass ->
        val dir = dirUri
        if (dir == null) {
            status = "Pick a sync folder first."
        } else {
            busy = true
            scope.launch {
                try {
                    val ok = withContext(Dispatchers.IO) {
                        (notesRepository as? RoomNotesRepository)?.checkpoint()
                        val deviceId = syncRepository.resolveDeviceId()
                        val zipBytes = BackupManager.buildZipBytes(context)
                            ?: return@withContext false
                        val payload = if (pass != null) {
                            runCatching { SyncCrypto.encrypt(zipBytes, pass) }.getOrNull()
                                ?: return@withContext false
                        } else {
                            zipBytes
                        }
                        val now = System.currentTimeMillis()
                        val name = SyncSnapshot.fileName(deviceId, now)
                        val current = notebooks
                        val manifest = SyncSnapshot.Manifest(
                            deviceId = deviceId,
                            createdAt = now,
                            notebookCount = current.size,
                            pageCount = current.sumOf { it.pageCount },
                            encrypted = pass != null,
                        )
                        val treeUri = runCatching { Uri.parse(dir) }.getOrNull()
                            ?: return@withContext false
                        SyncFolder.writeFile(context, treeUri, name, "application/zip", payload) &&
                            SyncFolder.writeFile(
                                context, treeUri, SyncSnapshot.manifestName(name),
                                "application/json",
                                SyncSnapshot.manifestToJson(manifest).toByteArray(),
                            )
                    }
                    status = if (ok) "Snapshot written to the sync folder."
                    else "Snapshot failed."
                } finally {
                    busy = false
                }
                refreshSnapshots()
            }
        }
    }

    val runImport: (CharArray?) -> Unit = { pass ->
        val target = importTarget
        val docUri = snapshotUris[target?.fileName]
        if (target == null || docUri == null) {
            importError = "Snapshot is no longer in the folder."
        } else {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val bytes = SyncFolder.readBytes(context.contentResolver, docUri)
                            ?: return@withContext BackupManager.RestoreResult.Error("Unreadable file.")
                        (notesRepository as? RoomNotesRepository)?.checkpoint()
                        AppDatabase.close()
                        BackupManager.importBackup(context, bytes, pass) {
                            AppDatabase.close()
                        }
                    }
                    when (result) {
                        BackupManager.RestoreResult.Success -> {
                            importDone = true
                            importTarget = null
                            importPassOpen = false
                        }
                        BackupManager.RestoreResult.WrongPassphrase -> {
                            importError = "Wrong passphrase."
                            importPassOpen = true
                        }
                        BackupManager.RestoreResult.InvalidBackup ->
                            importError = "Not a Vellum snapshot."
                        is BackupManager.RestoreResult.Error ->
                            importError = "Import failed: ${result.message ?: "unknown error"}"
                    }
                } finally {
                    busy = false
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Point Vellum at a folder Syncthing mirrors between your devices. " +
                "Snapshots are full copies (same bytes as backups, optionally " +
                "passphrase-encrypted). Importing replaces every note here, so it " +
                "always asks first and keeps a safety copy.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "Folder: ${dirUri?.let { Uri.parse(it).lastPathSegment } ?: "not set"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (ownId.isNotEmpty()) {
            Text(
                "This device: ${SyncSnapshot.shortDeviceId(ownId)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                Text(if (dirUri == null) "Pick folder" else "Change")
            }
            if (dirUri != null) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            syncRepository.clearSyncDir()
                            snapshots = emptyList()
                        }
                    },
                ) { Text("Disconnect") }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportSnapshot(null) }, enabled = dirUri != null && !busy) {
                Text("Snapshot now")
            }
            OutlinedButton(
                onClick = { passDialogForExport = true },
                enabled = dirUri != null && !busy,
            ) {
                Text("Encrypted…")
            }
            OutlinedButton(onClick = { refreshSnapshots() }, enabled = dirUri != null && !busy) {
                Text("Refresh")
            }
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        Text("From other devices", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (dirUri == null) {
            Text(
                "Pick a sync folder to see snapshots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (snapshots.isEmpty()) {
            Text(
                "Nothing here yet — snapshots from your other devices appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                snapshots.forEach { meta ->
                    val manifest = meta.manifest
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Device ${SyncSnapshot.shortDeviceId(meta.deviceId)} · " +
                                    syncDateFormat.format(Date(meta.createdAt)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                (manifest?.let { "${it.notebookCount} notebooks · ${it.pageCount} pages" }
                                    ?: meta.fileName) +
                                    if (manifest?.encrypted == true) " · encrypted" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { importTarget = meta }) {
                            Text("Import")
                        }
                    }
                }
            }
        }
    }

    if (passDialogForExport) {
        var pass by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passDialogForExport = false },
            title = { Text("Snapshot passphrase") },
            text = {
                Column {
                    Text(
                        "Anyone with the folder needs this to read the snapshot. " +
                            "It is never stored.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase (min 8 characters)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        isError = confirm.isNotEmpty() && confirm != pass,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        passDialogForExport = false
                        exportSnapshot(pass.toCharArray())
                    },
                    enabled = pass.length >= SyncCrypto.MIN_PASSPHRASE_CHARS && pass == confirm,
                ) { Text("Write snapshot") }
            },
            dismissButton = {
                TextButton(onClick = { passDialogForExport = false }) { Text("Cancel") }
            },
        )
    }

    importTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { importTarget = null },
            title = { Text("Import snapshot?") },
            text = {
                Text(
                    "Replace every note on this device with the snapshot from " +
                        "device ${SyncSnapshot.shortDeviceId(target.deviceId)} " +
                        "(${syncDateFormat.format(Date(target.createdAt))})? " +
                        "A safety copy is kept first; the app restarts afterwards." +
                        if (target.manifest?.encrypted == true) " A passphrase is needed next."
                        else "",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (target.manifest?.encrypted == true) {
                        importPassOpen = true
                    } else {
                        runImport(null)
                    }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { importTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (importPassOpen) {
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importPassOpen = false },
            title = { Text("Snapshot passphrase") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(importError!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { runImport(pass.toCharArray()) },
                    enabled = pass.length >= SyncCrypto.MIN_PASSPHRASE_CHARS,
                ) { Text("Unlock & import") }
            },
            dismissButton = {
                TextButton(onClick = { importPassOpen = false }) { Text("Cancel") }
            },
        )
    }

    if (importError != null && !importPassOpen && !importDone) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import") },
            text = { Text(importError!!) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("OK") }
            },
        )
    }

    if (importDone) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Import complete") },
            text = { Text("Your notes were replaced. Restart the app to continue.") },
            confirmButton = {
                TextButton(onClick = {
                    android.os.Process.killProcess(android.os.Process.myPid())
                }) { Text("Restart now") }
            },
        )
    }
}
