// Copyright (C) 2026 codeRed
// Vellum is free software: you can redistribute it and/or modify it under
// the terms of the GNU General Public License as published by the Free Software
// Foundation, either version 3 of the License, or (at your option) any later version.
package com.vellum.notes.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.MediaLoader
import com.vellum.notes.pdf.PdfCropRect
import com.vellum.notes.pdf.PdfImporter
import com.vellum.notes.pdf.PdfReaderTheme
import java.io.File

/**
 * Offline PDF reader: filmstrip of cached thumbnails, sepia/night tint overlays
 * (originals untouched — tint is applied to an in-memory copy), per-page
 * crop/trim of white margins (sidecar JSON), and annotate entry point.
 *
 * Ink annotation itself stays in the editor: [onAnnotatePage] receives the page
 * file so the caller can open/create the PDF-backed editor page.
 */
@Composable
fun PdfReaderScreen(
    onAnnotatePage: (pageFile: File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<Pair<File, File>>>(emptyList()) }
    var selected by remember { mutableStateOf(0) }
    var theme by remember { mutableStateOf(PdfReaderTheme.ORIGINAL) }
    var cropEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        pages = PdfImporter.thumbnails(context)
    }

    Column(modifier.fillMaxSize()) {
        // Theme switcher.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in PdfReaderTheme.entries) {
                FilterChip(
                    selected = theme == t,
                    onClick = { theme = t },
                    label = { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        // Main page view.
        val current = pages.getOrNull(selected)
        Box(
            Modifier.fillMaxWidth().weight(1f).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (current == null) {
                Text("No PDF pages imported yet.", style = MaterialTheme.typography.bodyLarge)
            } else {
                PdfReaderPage(
                    pageFile = current.first,
                    theme = theme,
                    cropEnabled = cropEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Crop + annotate actions.
        if (current != null) {
            var cropRect by remember(current.first, cropEnabled) {
                mutableStateOf(
                    if (cropEnabled) PdfImporter.loadCropRect(context, current.first.name) else null,
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = cropEnabled,
                    onClick = { cropEnabled = !cropEnabled },
                    label = { Text(if (cropEnabled) "Trimmed" else "Trim margins") },
                )
                if (cropEnabled) {
                    Button(onClick = {
                        val bmp = MediaLoader.decodeSampled(current.first, maxDimPx = 1024)
                        val detected = bmp?.let { PdfImporter.detectContentBounds(it) }
                        bmp?.recycle()
                        val rect: PdfCropRect? = detected
                        if (rect != null) {
                            PdfImporter.saveCropRect(context, current.first.name, rect)
                        }
                        cropRect = PdfImporter.loadCropRect(context, current.first.name)
                    }) {
                        Text("Auto-trim")
                    }
                    if (cropRect != null) {
                        Button(onClick = {
                            PdfImporter.saveCropRect(context, current.first.name, null)
                            cropRect = null
                        }) {
                            Text("Reset")
                        }
                    }
                }
                Button(onClick = { onAnnotatePage(current.first) }) {
                    Text("Annotate")
                }
            }
        }

        // Filmstrip.
        val listState = rememberLazyListState()
        LaunchedEffect(selected) {
            if (pages.isNotEmpty()) runCatching { listState.scrollToItem(selected) }
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(88.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(pages, key = { _, p -> p.first.name }) { index, (_, thumb) ->
                val bmp = remember(thumb.absolutePath) {
                    MediaLoader.decodeSampled(thumb, maxDimPx = 256, rgb565 = true)
                }
                DisposableEffect(thumb.absolutePath) {
                    onDispose { bmp?.recycle() }
                }
                Box(
                    Modifier.size(56.dp, 72.dp)
                        .border(
                            if (index == selected) 2.dp else 0.dp,
                            MaterialTheme.colorScheme.primary,
                        )
                        .clickable { selected = index },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        Image(
                            bmp.asImageBitmap(), contentDescription = "Page ${index + 1}",
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("${index + 1}")
                    }
                }
            }
        }
    }
}

/** Single page with optional crop + theme tint overlay (tint drawn over, file untouched). */
@Composable
private fun PdfReaderPage(
    pageFile: File,
    theme: PdfReaderTheme,
    cropEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val base = remember(pageFile.absolutePath) {
        MediaLoader.decodeSampled(pageFile, maxDimPx = 2048, rgb565 = true)
    }
    DisposableEffect(pageFile.absolutePath) {
        onDispose { base?.recycle() }
    }
    val crop = remember(pageFile.absolutePath, cropEnabled) {
        if (cropEnabled) PdfImporter.loadCropRect(context, pageFile.name) else null
    }
    if (base == null) {
        Text("Could not load page.")
        return
    }
    val shown = remember(base, theme, crop) {
        val cropped = if (crop != null && crop.isValid()) {
            val px = crop.toPixels(base.width, base.height)
            runCatching {
                Bitmap.createBitmap(
                    base, px[0], px[1],
                    (px[2] - px[0]).coerceAtLeast(1), (px[3] - px[1]).coerceAtLeast(1),
                )
            }.getOrNull() ?: base
        } else base
        if (theme == PdfReaderTheme.ORIGINAL) cropped
        else PdfImporter.tintedCopy(cropped, theme).also {
            if (cropped !== base) cropped.recycle()
        }
    }
    DisposableEffect(shown) {
        onDispose { if (shown !== base) shown.recycle() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(
            shown.asImageBitmap(), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
        )
        // Night veil legibility: keep a subtle scrim so white UI text stays readable.
        if (theme == PdfReaderTheme.NIGHT) {
            Box(
                Modifier.fillMaxSize().alpha(0.08f)
                    .background(Color.Black),
            )
        }
    }
}
