package com.vellum.notes.ui.home

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.BackupManager
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.data.RoomNotesRepository
import com.vellum.notes.model.Category
import com.vellum.notes.model.NoteType
import com.vellum.notes.model.Notebook
import com.vellum.notes.model.NotebookCovers
import com.vellum.notes.model.PaperTemplates
import com.vellum.notes.pdf.PdfImporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Noteshelf section shown in the main content area. */
sealed interface HomeSection {
    data object Home : HomeSection
    data object Starred : HomeSection
    data object Unfiled : HomeSection
    data object Archived : HomeSection
    data object Trash : HomeSection
    data class Category(val id: Long) : HomeSection
}

private fun HomeSection.title(categories: List<Category>): String = when (this) {
    HomeSection.Home -> "Home"
    HomeSection.Starred -> "Starred"
    HomeSection.Unfiled -> "Unfiled"
    HomeSection.Archived -> "Archived"
    HomeSection.Trash -> "Trash"
    is HomeSection.Category -> categories.firstOrNull { it.id == id }?.name ?: "Category"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    repository: NotesRepository,
    onOpenNotebook: (Long) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val notebooks by repository.notebooks.collectAsState(initial = emptyList())
    val trashed by repository.trashedNotebooks.collectAsState(initial = emptyList())
    val categories by repository.categories.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var section by remember { mutableStateOf<HomeSection>(HomeSection.Home) }
    // If the selected category disappears (deleted), fall back to Home.
    androidx.compose.runtime.LaunchedEffect(categories) {
        if (section is HomeSection.Category &&
            categories.none { it.id == (section as HomeSection.Category).id }
        ) {
            section = HomeSection.Home
        }
    }

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
                    repository.deleteNotebookPermanently(notebookId)
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
    var moving by remember { mutableStateOf<Notebook?>(null) }
    var categoryDialog by remember { mutableStateOf<CategoryDialog?>(null) }
    var backingUp by remember { mutableStateOf(false) }

    // Local backup export: versioned ZIP of db + assets via SAF. Offline.
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
    val launchBackup = {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        backupPicker.launch("vellum-backup-$stamp.zip")
    }

    var query by remember { mutableStateOf("") }
    var sortByName by remember { mutableStateOf(false) }

    // Noteshelf counts (non-trashed unless stated).
    val activeHome = notebooks.filterNot { it.isArchived }
    val starredCount = notebooks.count { it.isFavorite && !it.isArchived }
    val unfiledCount = notebooks.count { it.categoryId == null && !it.isArchived }
    val archivedCount = notebooks.count { it.isArchived }

    val sidebar = @Composable { closeDrawer: () -> Unit ->
        NoteshelfSidebar(
            section = section,
            categories = categories,
            homeCount = activeHome.size,
            starredCount = starredCount,
            unfiledCount = unfiledCount,
            archivedCount = archivedCount,
            trashCount = trashed.size,
            onSelect = { section = it; closeDrawer() },
            onNewCategory = { categoryDialog = CategoryDialog.New },
            onRenameCategory = { categoryDialog = CategoryDialog.Rename(it) },
            onDeleteCategory = { categoryDialog = CategoryDialog.Delete(it) },
            onOpenSettings = { closeDrawer(); onOpenSettings() },
            onOpenDiagnostics = { closeDrawer(); onOpenDiagnostics() },
            onBackup = { closeDrawer(); launchBackup() },
            backupEnabled = !backingUp,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            // Tablet/foldable: persistent Noteshelf sidebar next to the content.
            Row(Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(264.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    sidebar {}
                }
                HomeContent(
                    section = section,
                    categories = categories,
                    notebooks = notebooks,
                    trashed = trashed,
                    query = query,
                    onQuery = { query = it },
                    sortByName = sortByName,
                    onToggleSort = { sortByName = !sortByName },
                    repository = repository,
                    onOpenNotebook = onOpenNotebook,
                    onQuickNote = {
                        scope.launch {
                            val id = repository.createNotebook(
                                "Quick Note",
                                NoteType.NORMAL,
                            )
                            onOpenNotebook(id)
                        }
                    },
                    onNewNotebook = { showNewDialog = true },
                    onImportFile = { pdfPicker.launch(arrayOf("application/pdf")) },
                    importing = pdfImporting,
                    onEdit = { editing = it },
                    onMove = { moving = it },
                    onChangeCover = { coverEditing = it },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            // Phone: sidebar lives in the navigation drawer.
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    Surface(
                        modifier = Modifier.fillMaxHeight().width(300.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        sidebar { scope.launch { drawerState.close() } }
                    }
                },
            ) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(section.title(categories)) },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = "Noteshelf")
                                }
                            },
                            actions = {
                                IconButton(onClick = onOpenSettings) {
                                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                                }
                                IconButton(onClick = onOpenDiagnostics) {
                                    Icon(Icons.Filled.Science, contentDescription = "Labs")
                                }
                            },
                        )
                    },
                    floatingActionButton = {
                        FloatingActionButton(onClick = { showNewDialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New note")
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        HomeContent(
                            section = section,
                            categories = categories,
                            notebooks = notebooks,
                            trashed = trashed,
                            query = query,
                            onQuery = { query = it },
                            sortByName = sortByName,
                            onToggleSort = { sortByName = !sortByName },
                            repository = repository,
                            onOpenNotebook = onOpenNotebook,
                            onQuickNote = {
                                scope.launch {
                                    val id = repository.createNotebook("Quick Note", NoteType.NORMAL)
                                    onOpenNotebook(id)
                                }
                            },
                            onNewNotebook = { showNewDialog = true },
                            onImportFile = { pdfPicker.launch(arrayOf("application/pdf")) },
                            importing = pdfImporting,
                            onEdit = { editing = it },
                            onMove = { moving = it },
                            onChangeCover = { coverEditing = it },
                            modifier = Modifier.fillMaxSize(),
                            hideTitle = true,
                        )
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewNoteDialog(
            categories = categories,
            onDismiss = { showNewDialog = false },
            onConfirm = { name, type, coverId, templateId, categoryId ->
                scope.launch {
                    val id = repository.createNotebook(name, type, coverId = coverId, defaultTemplate = templateId)
                    if (categoryId != null) repository.setNotebookCategory(id, categoryId)
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

    moving?.let { nb ->
        MoveToCategoryDialog(
            categories = categories,
            currentId = nb.categoryId,
            onDismiss = { moving = null },
            onPick = { categoryId ->
                scope.launch { repository.setNotebookCategory(nb.id, categoryId) }
                moving = null
            },
        )
    }

    when (val dialog = categoryDialog) {
        is CategoryDialog.New -> CategoryEditDialog(
            title = "New category",
            onDismiss = { categoryDialog = null },
            onConfirm = { name ->
                scope.launch {
                    runCatching { repository.createCategory(name) }
                        .onFailure {
                            Toast.makeText(context, "Could not create category", Toast.LENGTH_SHORT).show()
                        }
                }
                categoryDialog = null
            },
        )
        is CategoryDialog.Rename -> CategoryEditDialog(
            title = "Rename category",
            initial = dialog.category.name,
            onDismiss = { categoryDialog = null },
            onConfirm = { name ->
                scope.launch { runCatching { repository.renameCategory(dialog.category.id, name) } }
                categoryDialog = null
            },
        )
        is CategoryDialog.Delete -> AlertDialog(
            onDismissRequest = { categoryDialog = null },
            title = { Text("Delete category?") },
            text = {
                Text("“${dialog.category.name}” will be removed. Its notebooks become Unfiled — nothing is deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { repository.deleteCategory(dialog.category.id) }
                    categoryDialog = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { categoryDialog = null }) { Text("Cancel") }
            },
        )
        null -> Unit
    }
}

/** Which category dialog is open. */
private sealed interface CategoryDialog {
    data object New : CategoryDialog
    data class Rename(val category: Category) : CategoryDialog
    data class Delete(val category: Category) : CategoryDialog
}

@Composable
private fun NoteshelfSidebar(
    section: HomeSection,
    categories: List<Category>,
    homeCount: Int,
    starredCount: Int,
    unfiledCount: Int,
    archivedCount: Int,
    trashCount: Int,
    onSelect: (HomeSection) -> Unit,
    onNewCategory: () -> Unit,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onBackup: () -> Unit,
    backupEnabled: Boolean,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "Noteshelf",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(12.dp))

        SidebarNavRow(
            label = "Home",
            count = homeCount,
            selected = section == HomeSection.Home,
            container = MaterialTheme.colorScheme.errorContainer,
            onClick = { onSelect(HomeSection.Home) },
        ) {
            Icon(Icons.Filled.Home, contentDescription = null)
        }
        SidebarNavRow(
            label = "Starred",
            count = starredCount,
            selected = section == HomeSection.Starred,
            container = MaterialTheme.colorScheme.tertiaryContainer,
            onClick = { onSelect(HomeSection.Starred) },
        ) {
            Icon(Icons.Filled.Star, contentDescription = null)
        }
        SidebarNavRow(
            label = "Unfiled",
            count = unfiledCount,
            selected = section == HomeSection.Unfiled,
            container = MaterialTheme.colorScheme.secondaryContainer,
            onClick = { onSelect(HomeSection.Unfiled) },
        ) {
            Icon(Icons.Filled.Inbox, contentDescription = null)
        }
        SidebarNavRow(
            label = "Trash",
            count = trashCount,
            selected = section == HomeSection.Trash,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = { onSelect(HomeSection.Trash) },
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null)
        }
        SidebarNavRow(
            label = "Archived",
            count = archivedCount,
            selected = section == HomeSection.Archived,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            onClick = { onSelect(HomeSection.Archived) },
        ) {
            Icon(Icons.Filled.Archive, contentDescription = null)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Categories",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(4.dp))
        categories.forEach { category ->
            CategoryRow(
                category = category,
                selected = section == HomeSection.Category(category.id),
                onClick = { onSelect(HomeSection.Category(category.id)) },
                onRename = { onRenameCategory(category) },
                onDelete = { onDeleteCategory(category) },
            )
        }
        TextButton(onClick = onNewCategory) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("New Category")
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onBackup, enabled = backupEnabled) {
                Icon(Icons.Filled.Save, contentDescription = "Back up notes")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
            IconButton(onClick = onOpenDiagnostics) {
                Icon(Icons.Filled.Science, contentDescription = "Labs")
            }
        }
    }
}

@Composable
private fun SidebarNavRow(
    label: String,
    count: Int,
    selected: Boolean,
    container: Color,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) container else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp))
                .background(container.copy(alpha = 0.7f)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (count > 0) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryRow(
    category: Category,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (selected) MaterialTheme.colorScheme.inverseSurface
                    else Color.Transparent
                )
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.inverseOnSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                category.name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (category.notebookCount > 0) {
                Text(
                    "${category.notebookCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.inverseOnSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
        }
    }
}

@Composable
private fun HomeContent(
    section: HomeSection,
    categories: List<Category>,
    notebooks: List<Notebook>,
    trashed: List<Notebook>,
    query: String,
    onQuery: (String) -> Unit,
    sortByName: Boolean,
    onToggleSort: () -> Unit,
    repository: NotesRepository,
    onOpenNotebook: (Long) -> Unit,
    onQuickNote: () -> Unit,
    onNewNotebook: () -> Unit,
    onImportFile: () -> Unit,
    importing: Boolean,
    onEdit: (Notebook) -> Unit,
    onMove: (Notebook) -> Unit,
    onChangeCover: (Notebook) -> Unit,
    modifier: Modifier = Modifier,
    hideTitle: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val inTrash = section == HomeSection.Trash

    val base: List<Notebook> = when (section) {
        HomeSection.Home -> notebooks.filterNot { it.isArchived }
        HomeSection.Starred -> notebooks.filter { it.isFavorite && !it.isArchived }
        HomeSection.Unfiled -> notebooks.filter { it.categoryId == null && !it.isArchived }
        HomeSection.Archived -> notebooks.filter { it.isArchived }
        HomeSection.Trash -> trashed
        is HomeSection.Category -> notebooks.filter { it.categoryId == section.id }
    }
    val visible = base
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
        .sortedWith(
            if (sortByName) compareBy { it.title.lowercase() }
            else compareByDescending { it.updatedAt }
        )

    Column(modifier.fillMaxSize()) {
        if (!hideTitle) {
            Text(
                section.title(categories),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }

        if (!inTrash) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickActionCard(
                    label = "Quick Note",
                    onClick = onQuickNote,
                ) {
                    Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                QuickActionCard(
                    label = "New Notebook",
                    onClick = onNewNotebook,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                QuickActionCard(
                    label = if (importing) "Importing…" else "Import File",
                    onClick = onImportFile,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(12.dp))
        } else if (trashed.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                TextButton(
                    onClick = { scope.launch { repository.emptyTrash() } },
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Empty trash")
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search notes") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        TextButton(
            onClick = onToggleSort,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(if (sortByName) "Sort: A–Z" else "Sort: Recent")
        }

        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Nothing here yet", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (section) {
                            HomeSection.Trash -> "Deleted notes land here and can be restored."
                            else -> "Use Quick Note or New Notebook to start writing."
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(visible, key = { it.id }) { nb ->
                    if (inTrash) {
                        TrashCard(
                            notebook = nb,
                            onRestore = { scope.launch { repository.restoreNotebook(nb.id) } },
                            onDeleteForever = {
                                scope.launch { repository.deleteNotebookPermanently(nb.id) }
                            },
                        )
                    } else {
                        ShelfNotebookCard(
                            notebook = nb,
                            onClick = { onOpenNotebook(nb.id) },
                            onRename = { onEdit(nb) },
                            onToggleFavorite = {
                                scope.launch { repository.toggleFavorite(nb.id) }
                            },
                            onMove = { onMove(nb) },
                            onDuplicate = {
                                scope.launch { repository.duplicateNotebook(nb.id) }
                            },
                            onArchive = {
                                scope.launch { repository.setArchived(nb.id, !nb.isArchived) }
                            },
                            onChangeCover = { onChangeCover(nb) },
                            onTrash = {
                                scope.launch { repository.deleteNotebook(nb.id) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private val shelfDateFormat = SimpleDateFormat("MM/dd/yy, h:mm a", Locale.US)

@Composable
private fun ShelfNotebookCard(
    notebook: Notebook,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMove: () -> Unit,
    onDuplicate: () -> Unit,
    onArchive: () -> Unit,
    onChangeCover: () -> Unit,
    onTrash: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val cover = NotebookCovers.byId(notebook.coverId)
    Column(
        modifier = Modifier.defaultMinSize(minWidth = 140.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(cover.primaryArgb), Color(cover.secondaryArgb)),
                    ),
                ),
        ) {
            if (notebook.isFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(18.dp),
                )
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Notebook options",
                        tint = Color.White,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(
                        text = { Text(if (notebook.isFavorite) "Unstar" else "Star") },
                        onClick = { menuOpen = false; onToggleFavorite() },
                    )
                    DropdownMenuItem(text = { Text("Move to…") }, onClick = { menuOpen = false; onMove() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Change cover") }, onClick = { menuOpen = false; onChangeCover() })
                    DropdownMenuItem(
                        text = { Text(if (notebook.isArchived) "Unarchive" else "Archive") },
                        onClick = { menuOpen = false; onArchive() },
                    )
                    DropdownMenuItem(text = { Text("Move to trash") }, onClick = { menuOpen = false; onTrash() })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            notebook.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                shelfDateFormat.format(Date(notebook.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (notebook.type == NoteType.CLASSROOM) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Mic,
                    contentDescription = "Classroom",
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TrashCard(
    notebook: Notebook,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    val cover = NotebookCovers.byId(notebook.coverId)
    Column(
        modifier = Modifier.defaultMinSize(minWidth = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(cover.primaryArgb), Color(cover.secondaryArgb)),
                    ),
                ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            notebook.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row {
            TextButton(onClick = onRestore) {
                Icon(Icons.Filled.RestoreFromTrash, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Restore")
            }
            TextButton(onClick = onDeleteForever) {
                Text("Delete")
            }
        }
    }
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
private fun CoverPickerDialog(
    currentCoverId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cover") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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

@Composable
private fun NewNoteDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: NoteType, coverId: String, templateId: String, categoryId: Long?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(NoteType.NORMAL) }
    var coverId by remember { mutableStateOf("TEAL") }
    var templateId by remember { mutableStateOf("RULED") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true }
                        .border(
                            1.dp, MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        categories.firstOrNull { it.id == categoryId }?.name ?: "Unfiled",
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.Folder, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Unfiled") },
                        onClick = { categoryId = null; expanded = false },
                    )
                    categories.forEach { category ->
                        DropdownMenuItem(
                            text = { Text(category.name) },
                            onClick = { categoryId = category.id; expanded = false },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Cover", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PaperTemplates.ALL.forEach { t ->
                        val selected = templateId == t.id
                        Box(
                            modifier = Modifier
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .clickable { templateId = t.id }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(t.label, style = MaterialTheme.typography.labelLarge)
                        }
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
            TextButton(onClick = { onConfirm(name, type, coverId, templateId, categoryId) }) { Text("Create") }
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

@Composable
private fun MoveToCategoryDialog(
    categories: List<Category>,
    currentId: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CategoryOption(
                    label = "Unfiled",
                    selected = currentId == null,
                    onClick = { onPick(null) },
                )
                categories.forEach { category ->
                    CategoryOption(
                        label = "${category.name} (${category.notebookCount})",
                        selected = currentId == category.id,
                        onClick = { onPick(category.id) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun CategoryOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

@Composable
private fun CategoryEditDialog(
    title: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
