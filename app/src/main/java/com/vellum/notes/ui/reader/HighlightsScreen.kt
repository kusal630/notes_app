package com.vellum.notes.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.NotesRepository
import com.vellum.notes.model.BookHighlight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val highlightDateFormat = SimpleDateFormat("MM/dd/yy, h:mm a", Locale.US)

/**
 * Review list of every saved read-mode highlight, grouped by book. Tapping a
 * highlight jumps back into read mode on its exact page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightsScreen(
    repository: NotesRepository,
    onOpenHighlight: (notebookId: Long, pageId: Long) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val highlights by repository.allHighlights.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }

    val visible = remember(highlights, query) {
        if (query.isBlank()) highlights
        else highlights.filter {
            it.notebookTitle.contains(query, ignoreCase = true) ||
                it.pageTitle.contains(query, ignoreCase = true)
        }
    }
    val grouped = remember(visible) { visible.groupBy { it.notebookTitle } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Highlights") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search highlights") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            if (highlights.isEmpty())
                                "No highlights yet — open a book in read mode and sweep the highlighter over key lines."
                            else "No highlights match “$query”.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 32.dp),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    grouped.forEach { (book, items) ->
                        item(key = "header-$book") {
                            Text(
                                book.ifBlank { "Untitled book" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        items(items, key = { it.id }) { h ->
                            HighlightRow(
                                highlight = h,
                                onClick = { onOpenHighlight(h.notebookId, h.pageId) },
                                onDelete = {
                                    scope.launch { repository.deleteHighlight(h.id) }
                                },
                            )
                        }
                    }
                    item { Spacer(Modifier.size(88.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HighlightRow(
    highlight: BookHighlight,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(highlight.colorArgb)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                highlight.pageTitle.take(2).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Black.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                highlight.pageTitle.ifBlank { "Page" },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                highlightDateFormat.format(Date(highlight.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(Color(highlight.colorArgb)),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete highlight")
        }
    }
}
