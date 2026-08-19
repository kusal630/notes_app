package com.premiumnotes.ui.home

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Science
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
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.model.Notebook
import com.premiumnotes.model.NoteType
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

    var showNewDialog by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Notebook?>(null) }

    // Which note type to show: null = all, otherwise only that type.
    var filter by remember { mutableStateOf<NoteType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Premium Notes") },
                actions = {
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
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
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
                            }
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewNoteDialog(
            onDismiss = { showNewDialog = false },
            onConfirm = { name, type ->
                scope.launch {
                    val id = repository.createNotebook(name, type)
                    // A new classroom note opens straight into the canvas with the audio
                    // sidebar ready to record; a normal note stays on the home screen.
                    if (type == NoteType.CLASSROOM) onOpenNotebook(id)
                }
                showNewDialog = false
            }
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
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
private fun NewNoteDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: NoteType) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(NoteType.NORMAL) }
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
            TextButton(onClick = { onConfirm(name, type) }) { Text("Create") }
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