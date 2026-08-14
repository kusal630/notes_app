package com.premiumnotes.ui.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.premiumnotes.input.ClassifiedFrame
import com.premiumnotes.input.ContactClassification
import com.premiumnotes.input.InputCapabilities
import com.premiumnotes.input.InputFrame
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.input.PalmRejectionMode
import com.premiumnotes.input.PalmRejectionSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

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
    settings: PalmRejectionSettings,
    onBack: () -> Unit,
) {
    var frame by remember { mutableStateOf<ClassifiedFrame?>(null) }
    var inputFrame by remember { mutableStateOf<InputFrame?>(null) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Palm Rejection Test") },
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
                        onClick = { settings.mode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    ) {
                        Text(mode.name)
                    }
                }
            }
        }

        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            factory = { ctx ->
                DiagnosticsTouchView(ctx, engine, capabilities.pxPerMm).also { view ->
                    view.onFrame = { input, classified ->
                        inputFrame = input
                        frame = classified
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
                Text("Input capabilities", style = MaterialTheme.typography.titleMedium)
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
                        color = Color(0xFFB00020),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Text("Live contact data", style = MaterialTheme.typography.titleMedium)
                val contacts = frame?.contacts.orEmpty()
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
                            color = Color(0xFF2E5BFF),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val penDim = contacts.firstOrNull {
                        it.classification == ContactClassification.WRITING
                    }?.contact?.maxDimMm
                    Button(
                        enabled = penDim != null && penDim!! > 0f,
                        onClick = { settings.calibration = settings.calibration.copy(penMaxDimMm = penDim) }
                    ) { Text("Save pen size ${penDim?.let { "%.1f mm".format(it) } ?: ""}") }

                    val fingerDim = contacts.firstOrNull {
                        it.classification == ContactClassification.FINGER
                    }?.contact?.maxDimMm
                    OutlinedButton(
                        enabled = fingerDim != null && fingerDim!! > 0f,
                        onClick = { settings.calibration = settings.calibration.copy(fingerMaxDimMm = fingerDim) }
                    ) { Text("Save finger ${fingerDim?.let { "%.1f".format(it) } ?: ""}") }

                    val palmDim = contacts.firstOrNull {
                        it.classification == ContactClassification.PALM
                    }?.contact?.maxDimMm
                    OutlinedButton(
                        enabled = palmDim != null && palmDim!! > 0f,
                        onClick = { settings.calibration = settings.calibration.copy(palmMaxDimMm = palmDim) }
                    ) { Text("Save palm ${palmDim?.let { "%.1f".format(it) } ?: ""}") }
                }

                Text("Event: ${inputFrame?.action ?: "—"}  pointers: ${inputFrame?.pointerCount ?: 0}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ContactRow(cc: com.premiumnotes.input.ClassifiedContact, settings: PalmRejectionSettings) {
    val c = cc.contact
    val color = when (cc.classification) {
        ContactClassification.WRITING -> Color(0xFF2E5BFF)
        ContactClassification.FINGER -> Color(0xFF00A86B)
        ContactClassification.PALM -> Color(0xFFFF4D4D)
        ContactClassification.ERASER -> Color(0xFF9C27B0)
        ContactClassification.REJECTED -> Color(0xFF9E9E9E)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f))
            .padding(8.dp)
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