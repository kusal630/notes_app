package com.vellum.notes.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.vellum.notes.data.SettingsRepository
import com.vellum.notes.input.ClassifiedFrame
import com.vellum.notes.input.ContactClassification
import com.vellum.notes.input.InputCapabilities
import com.vellum.notes.input.InputFrame
import com.vellum.notes.input.PalmRejectionEngine
import com.vellum.notes.input.PalmRejectionMode
import com.vellum.notes.input.PalmRejectionSettings
import com.vellum.notes.input.PalmZone
import com.vellum.notes.input.PalmZoneMode
import com.vellum.notes.input.PalmZoneSide
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PALM REJECTION TEST / CALIBRATION screen (requirement 41).
 * Shows exactly what the device reports for every live contact — tool type, contact
 * size, major/minor axes, pressure, pointer ID, classification and confidence — and
 * lets the engineer measure their hardware for calibration instead of guessing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    engine: PalmRejectionEngine,
    capabilities: InputCapabilities,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    var frame by remember { mutableStateOf<ClassifiedFrame?>(null) }
    var inputFrame by remember { mutableStateOf<InputFrame?>(null) }
    var measuringPalm by remember { mutableStateOf(false) }
    var palmPeakMm by remember { mutableStateOf(0f) }
    val settings by settingsRepository.settingsFlow.collectAsState(initial = PalmRejectionSettings())
    val scope = rememberCoroutineScope()

    // While "measure my palm" is active, the largest palm contact seen in a 2s window
    // becomes the zone size (with padding), saved back into settings.
    LaunchedEffect(measuringPalm) {
        if (measuringPalm) {
            palmPeakMm = 0f
            delay(2000)
            if (palmPeakMm > 0f) {
                settingsRepository.updateSettings {
                    palmZone = PalmZone.fromPalm(palmPeakMm, palmPeakMm * 0.75f, palmZone.side)
                }
            }
            measuringPalm = false
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Column {
                    Text("Labs", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Palm rejection test & calibration",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val modes = PalmRejectionMode.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = settings.mode == mode,
                        onClick = { scope.launch { settingsRepository.updateSettings { this.mode = mode } } },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(humanizeMode(mode.name))
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .sizeIn(minHeight = 200.dp),
            factory = { ctx ->
                DiagnosticsTouchView(ctx, engine, capabilities.pxPerMm).also { view ->
                    view.onFrame = { input, classified ->
                        inputFrame = input
                        frame = classified
                        if (measuringPalm) {
                            val peak = classified.contacts
                                .filter { it.classification == ContactClassification.PALM }
                                .maxOfOrNull { it.contact.maxDimMm }
                            if (peak != null && peak > palmPeakMm) palmPeakMm = peak
                        }
                    }
                }
            }
        )

        // Live readout panel
        Surface(tonalElevation = 2.dp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LabsCard(title = "Input capabilities") {
                Text(
                    "Stylus device: ${if (capabilities.supportsStylus) "yes" else "no"}   " +
                        "Stylus tool type exposed: ${if (capabilities.supportsStylusToolType) "yes" else "no"}   " +
                        "Density: ${"%.1f".format(capabilities.pxPerMm)} px/mm",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!capabilities.supportsStylusToolType) {
                    Text(
                        "This device does NOT report a stylus tool type for passive styluses — " +
                            "contacts arrive as FINGER/UNKNOWN. Palm rejection is therefore purely software-based.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                }

                val contacts = frame?.contacts.orEmpty()
                LabsCard(title = "Live contact data") {
                ClassificationLegend()
                if (contacts.isEmpty()) {
                    Text("No contact. Touch the surface with pen, finger or palm.", style = MaterialTheme.typography.bodySmall)
                } else {
                    contacts.forEach { cc ->
                        val c = cc.contact
                        ContactRow(cc, settings)
                    }
                    frame?.activeWritingPointerId?.let { active ->
                        Text(
                            "Active writing pointer: $active (writing lock engaged)",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val penDim = contacts.firstOrNull {
                        it.classification == ContactClassification.WRITING
                    }?.contact?.maxDimMm
                    Button(
                        enabled = penDim != null && penDim!! > 0f,
                        onClick = { scope.launch { settingsRepository.updateSettings { this.calibration = this.calibration.copy(penMaxDimMm = penDim) } } }
                    ) { Text("Save pen size ${penDim?.let { "%.1f mm".format(it) } ?: ""}") }

                    val fingerDim = contacts.firstOrNull {
                        it.classification == ContactClassification.FINGER
                    }?.contact?.maxDimMm
                    OutlinedButton(
                        enabled = fingerDim != null && fingerDim!! > 0f,
                        onClick = { scope.launch { settingsRepository.updateSettings { this.calibration = this.calibration.copy(fingerMaxDimMm = fingerDim) } } }
                    ) { Text("Save finger ${fingerDim?.let { "%.1f".format(it) } ?: ""}") }

                    val palmDim = contacts.firstOrNull {
                        it.classification == ContactClassification.PALM
                    }?.contact?.maxDimMm
                    OutlinedButton(
                        enabled = palmDim != null && palmDim!! > 0f,
                        onClick = { scope.launch { settingsRepository.updateSettings { this.calibration = this.calibration.copy(palmMaxDimMm = palmDim) } } }
                    ) { Text("Save palm ${palmDim?.let { "%.1f".format(it) } ?: ""}") }
                }
                }

                LabsCard(title = "Palm rest zone") {
                Text(
                    "Auto mode rejects palms automatically by contact size (bigger than a " +
                        "finger) with no box on the canvas. Manual mode reserves a draggable " +
                        "blue area you can place with the grip on the canvas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SingleChoiceSegmentedButtonRow {
                        PalmZoneMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.palmZone.mode == mode,
                                onClick = { scope.launch {
                                    settingsRepository.updateSettings { palmZone = palmZone.copy(mode = mode) }
                                } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PalmZoneMode.entries.size),
                            ) { Text(humanizeMode(mode.name)) }
                        }
                    }
                }

                if (settings.palmZone.mode == PalmZoneMode.AUTO) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SingleChoiceSegmentedButtonRow {
                            PalmZoneSide.entries.forEachIndexed { index, side ->
                                SegmentedButton(
                                    selected = settings.palmZone.side == side,
                                    onClick = { scope.launch {
                                        settingsRepository.updateSettings { palmZone = palmZone.copy(side = side) }
                                    } },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = PalmZoneSide.entries.size),
                                ) { Text(humanizeMode(side.name)) }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val hasPalmContact = contacts.any { it.classification == ContactClassification.PALM }
                    Button(
                        enabled = hasPalmContact || measuringPalm,
                        onClick = { measuringPalm = !measuringPalm }
                    ) {
                        Text(
                            if (measuringPalm) {
                                "Rest palm for 2s… peak ${"%.1f".format(palmPeakMm)} mm"
                            } else {
                                "Measure my palm"
                            }
                        )
                    }
                }
                if (settings.palmZone.enabled) {
                    Text(
                        "Current zone: ${"%.0f".format(settings.palmZone.widthMm)} × " +
                            "${"%.0f".format(settings.palmZone.heightMm)} mm · ${settings.palmZone.mode.name}" +
                            (if (settings.palmZone.mode == PalmZoneMode.AUTO) " · ${settings.palmZone.side.name}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                }

                Text("Event: ${inputFrame?.action ?: "—"}  pointers: ${inputFrame?.pointerCount ?: 0}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LabsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun humanizeMode(name: String): String =
    name.lowercase().replaceFirstChar { it.uppercase() }

/** Color key for the live contact classifications. */
@Composable
private fun ClassificationLegend() {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(Color(0xFF2E5BFF), "Writing")
        LegendDot(Color(0xFF00A86B), "Finger")
        LegendDot(Color(0xFFFF4D4D), "Palm")
        LegendDot(Color(0xFF9C27B0), "Eraser")
        LegendDot(Color(0xFFFFB300), "Candidate")
        LegendDot(Color(0xFF90A4AE), "Resting")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ContactRow(cc: com.vellum.notes.input.ClassifiedContact, settings: PalmRejectionSettings) {
    val c = cc.contact
    val color = when (cc.classification) {
        ContactClassification.WRITING -> Color(0xFF2E5BFF)
        ContactClassification.FINGER -> Color(0xFF00A86B)
        ContactClassification.PALM -> Color(0xFFFF4D4D)
        ContactClassification.ERASER -> Color(0xFF9C27B0)
        ContactClassification.REJECTED -> Color(0xFF9E9E9E)
        ContactClassification.CANDIDATE -> Color(0xFFFFB300)
        ContactClassification.RESTING -> Color(0xFF90A4AE)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp)
    ) {
        Text(
            "Pointer $c.pointerId  ·  ${c.toolType.name}  ·  ${cc.classification.name}  ·  " +
                "confidence ${(cc.confidence * 100).toInt()}%",
            color = color,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "size=${"%.2f".format(c.size)}  major=${"%.1f".format(c.toolMajorMm)} mm  " +
                "minor=${"%.1f".format(c.toolMinorMm)} mm  pressure=${"%.2f".format(c.pressure)}  " +
                "speed=${cc.speedMmPerSec.toInt()} mm/s  down=${cc.durationMs}ms",
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "reason=${cc.reason.name}  threshold=${"%.1f".format(cc.effectiveThresholdMm)} mm  " +
                "eff. pen≤${"%.1f".format(settings.effectiveWritingMaxMm())}  " +
                "finger≤${"%.1f".format(settings.effectiveFingerMaxMm())}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}