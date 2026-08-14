package com.premiumnotes.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Premium Notes") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = onOpenDiagnostics) {
                        Icon(Icons.Filled.Science, contentDescription = "Palm rejection test")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New notebook")
            }
        }
    ) { padding ->
        val visible = notebooks.filterNot { it.isArchived }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No notebooks yet", style = MaterialTheme.typography.titleLarge)
                    Text("Tap + to create your first notebook")
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(220.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
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

    if (showNewDialog) {
        TitleDialog(
            title = "New notebook",
            initial = "",
            confirm = "Create",
            onDismiss = { showNewDialog = false },
            onConfirm = { name ->
                scope.launch { repository.createNotebook(name) }
                showNewDialog = false
            }
        )
    }

    editing?.let { nb ->
        TitleDialog(
            title = "Rename notebook",
            initial = nb.title,
            confirm = "Rename",
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
            Text("${notebook.pageCount} pages", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TitleDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Name") })
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(confirm) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}