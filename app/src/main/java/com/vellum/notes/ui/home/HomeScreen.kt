package com.vellum.notes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.provider.OpenableColumns
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
import com.vellum.notes.model.NotebookCovers
import com.vellum.notes.model.PaperTemplates
import com.vellum.notes.pdf.PdfImporter
import com.vellum.notes.data.ImageStore
import java.io.File
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.data.BackupManager
import com.vellum.notes.data.RoomNotesRepository
import com.vellum.notes.model.Notebook
import com.vellum.notes.model.NoteType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: NotesRepository,
    onOpenNotebook: (Long) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val notebooks by repository.notebooks.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var coverEditing by remember { mutableStateOf<Notebook?>(null) }
    var pdfImporting by remember { mutableStateOf(false) }

    // Offline PDF import: pick a file, rasterize every page into app-private
    // storage, and build a PDF-backed notebook whose pages carry the raster
    // underlay beneath the ink.
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        pdfImporting = true
        scope.launch {
            try {
                val name = queryDisplayName(context, uri) ?: "Imported PDF"
                val notebookId = repository.createNotebook(name, NoteType.NORMAL)
                val files = withContext(Dispatchers.IO) {
                    PdfImporter.rasterize(context, uri, notebookId)
                }
                if (files.isEmpty()) {
                    repository.deleteNotebook(notebookId)
                    Toast.makeText(context, "Could not read PDF", Toast.LENGTH_SHORT).show()
                } else {
                    files.forEachIndexed { index, f ->
                        val pageId = repository.createPage(notebookId, title = "Page ${index + 1}")
                        repository.setPagePdfBackground(pageId, index, f.name)
                    }
                }
            } catch (t: Throwable) {
                Toast.makeText(context, "PDF import failed: ${t.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pdfImporting = false
            }
        }
    }

    var showNewDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Notebook?>(null) }
    var backingUp by remember { mutableStateOf(false) }

    // Local backup export (S8): versioned ZIP of db + assets via SAF. Offline.
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        backingUp = true
        scope.launch {
            try {
                (repository as? RoomNotesRepository)?.checkpoint()
                val count = withContext(Dispatchers.IO) {
                    BackupManager.exportZip(context, uri)
                }
                Toast.makeText(
                    context,
                    if (count != null) "Backup saved ($count files)" else "Backup failed",
                    Toast.LENGTH_LONG,
                ).show()
            } catch (t: Throwable) {
                Toast.makeText(context, "Backup failed: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                backingUp = false
            }
        }
    }

    // Which note type to show: null = all, otherwise only that type.
    var filter by remember { mutableStateOf<NoteType?>(null) }
    // Premium home: search + sort. Favorites always float to the top.
    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }
    var favoritesOnly by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vellum") },
                actions = {
                    IconButton(
                        onClick = {
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                                .format(java.util.Date())
                            backupPicker.launch("vellum-backup-$stamp.zip")
                        },
                        enabled = !backingUp,
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Back up notes")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.Science, contentDescription = "Labs")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    icon = { Icon(Icons.Filled.PictureAsPdf, contentDescription = null) },
                    text = { Text(if (pdfImporting) "Importing…" else "Import PDF") },
                )
                Spacer(Modifier.height(12.dp))
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Two note types, side by side: normal handwritten notes and classroom notes
            // (the same canvas plus an on-device audio transcript sidebar).
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                listOf<NoteType?>(null, NoteType.NORMAL, NoteType.CLASSROOM)
                    .forEachIndexed { index, type ->
                        SegmentedButton(
                            selected = filter == type,
                            onClick = { filter = type },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                        ) { Text(type?.let { if (it == NoteType.CLASSROOM) "Classroom" else "Normal" } ?: "All") }
                    }
            }

            val visible = notebooks
                .filterNot { it.isArchived }
                .filter { filter == null || it.type == filter }
                .filter { !favoritesOnly || it.isFavorite }
                .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Notebook> { it.isFavorite }.then(
                        if (sortByName) compareBy { it.title.lowercase() }
                        else compareByDescending { it.updatedAt }
                    )
                )

            // Search + sort row (premium productivity: find any notebook in seconds).
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { sortByName = !sortByName }) {
                    Text(if (sortByName) "Sort: A–Z" else "Sort: Recent")
                }
                TextButton(onClick = { favoritesOnly = !favoritesOnly }) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = null,
                        tint = if (favoritesOnly) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Favorites")
                }
            }

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No notes here yet", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (filter == NoteType.CLASSROOM)
                                "Tap + to create a classroom note — write by hand and record " +
                                    "the lecture into the sidebar."
                            else "Tap + to create your first note"
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(visible, key = { it.id }) { nb ->
                        NotebookCard(
                            notebook = nb,
                            onClick = { onOpenNotebook(nb.id) },
                            onEdit = { editing = nb },
                            onDelete = {
                                scope.launch { repository.deleteNotebook(nb.id) }
                            },
                            onToggleFavorite = {
                                scope.launch { repository.toggleFavorite(nb.id) }
                            },
                            onArchive = {
                                scope.launch { repository.setArchived(nb.id, true) }
                            },
                            onChangeCover = { coverEditing = nb }
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewNoteDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { name, type, coverId, templateId ->
                scope.launch {
                    val id = repository.createNotebook(name, type, coverId = coverId, defaultTemplate = templateId)
                    // A new classroom note opens straight into the canvas with the audio
                    // sidebar ready to record; a normal note stays on the home screen.
                    if (type == NoteType.CLASSROOM) onOpenNotebook(id)
                }
                showNewDialog = false
            }
        )
    }

    coverEditing?.let { nb ->
        CoverPickerDialog(
            currentCoverId = nb.coverId,
            onDismiss = { coverEditing = null },
            onPick = { coverId ->
                scope.launch { repository.setNotebookCover(nb.id, coverId) }
                coverEditing = null
            },
        )
    }

    editing?.let { nb ->
        RenameDialog(
            initial = nb.title,
            onDismiss = { editing = null },
            onConfirm = { name ->
                scope.launch { repository.renameNotebook(nb.id, name) }
                editing = null
            }
        )
    }
}

@Composable
private fun NotebookCard(
    notebook: Notebook,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onArchive: () -> Unit,
    onChangeCover: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cover = NotebookCovers.byId(notebook.coverId)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        // Premium cover banner: gradient (or pattern) from the cover library.
        Box(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(cover.primaryArgb), Color(cover.secondaryArgb)),
                    ),
                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ),
        )
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    notebook.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = "Favorite",
                        tint = if (notebook.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text("Change cover") }, onClick = { menuOpen = false; onChangeCover() })
                        DropdownMenuItem(text = { Text("Archive") }, onClick = { menuOpen = false; onArchive() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${notebook.pageCount} pages", style = MaterialTheme.typography.bodySmall)
                if (notebook.type == NoteType.CLASSROOM) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        "Classroom",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverPickerDialog(
    currentCoverId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cover") },
        text = {
            Column {
                NotebookCovers.ALL.chunked(4).forEach { rowCovers ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowCovers.forEach { cover ->
                            val selected = cover.id == currentCoverId
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(Color(cover.primaryArgb), Color(cover.secondaryArgb)),
                                        ),
                                        RoundedCornerShape(14.dp),
                                    )
                                    .then(
                                        if (selected) Modifier.border(
                                            2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp),
                                        ) else Modifier
                                    )
                                    .clickable { onPick(cover.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Display name of a SAF document, or null. */
private fun queryDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()
}

@Composable
private fun NewNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: NoteType, coverId: String, templateId: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(NoteType.NORMAL) }
    var coverId by remember { mutableStateOf("TEAL") }
    var templateId by remember { mutableStateOf("RULED") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    NoteType.entries.forEachIndexed { index, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = NoteType.entries.size),
                        ) { Text(if (t == NoteType.CLASSROOM) "Classroom" else "Normal") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Cover", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NotebookCovers.ALL.forEach { cover ->
                        val selected = cover.id == coverId
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(cover.primaryArgb), Color(cover.secondaryArgb)),
                                    ),
                                    RoundedCornerShape(10.dp),
                                )
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp),
                                    ) else Modifier
                                )
                                .clickable { coverId = cover.id },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Paper", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PaperTemplates.ALL.forEachIndexed { index, t ->
                        SegmentedButton(
                            selected = templateId == t.id,
                            onClick = { templateId = t.id },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = PaperTemplates.ALL.size),
                        ) { Text(t.label, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (type == NoteType.CLASSROOM)
                        "A classroom note opens the handwriting canvas plus an on-device " +
                            "audio transcript sidebar where you can record and summarize the lecture."
                    else "A normal handwritten note — the classic notebook experience.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, type, coverId, templateId) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename note") },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}