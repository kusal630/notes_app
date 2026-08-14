package com.premiumnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.premiumnotes.data.NotesRepository
import com.premiumnotes.data.SettingsRepository
import com.premiumnotes.input.InputCapabilities
import com.premiumnotes.input.PalmRejectionEngine
import com.premiumnotes.input.PalmRejectionMode
import com.premiumnotes.input.PalmRejectionSettings
import com.premiumnotes.input.SmoothingMode
import com.premiumnotes.ui.diagnostics.DiagnosticsScreen
import com.premiumnotes.ui.editor.EditorScreen
import com.premiumnotes.ui.home.HomeScreen
import com.premiumnotes.ui.theme.PremiumNotesTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val EDITOR = "editor/{notebookId}"
    const val DIAGNOSTICS = "diagnostics"
    const val SETTINGS = "settings"

    fun editor(notebookId: Long) = "editor/$notebookId"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PremiumNotesTheme {
                NotesAppRoot()
            }
        }
    }
}

@Composable
fun NotesAppRoot() {
    val navController = rememberNavController()
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as PremiumNotesApp).container

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                repository = container.notesRepository,
                onOpenNotebook = { navController.navigate(Routes.editor(it)) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }
        composable(Routes.EDITOR) { backStackEntry ->
            val notebookId = backStackEntry.arguments?.getString("notebookId")?.toLongOrNull() ?: 0L
            EditorScreen(
                notebookId = notebookId,
                repository = container.notesRepository,
                capabilities = container.inputCapabilities,
                engine = container.palmRejectionEngine,
                settingsFlow = container.palmRejectionSettingsFlow,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                engine = container.palmRejectionEngine,
                capabilities = container.inputCapabilities,
                settingsRepository = container.settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settingsRepository = (LocalContext.current.applicationContext as PremiumNotesApp).container.settingsRepository,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    val settings by settingsRepository.settingsFlow.collectAsState(initial = PalmRejectionSettings())
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            SettingsContent(settings = settings, onSettingChange = { newSettings ->
                scope.launch {
                    settingsRepository.updateSettings { 
                        this.mode = newSettings.mode 
                        this.sensitivity = newSettings.sensitivity
                        this.writingMaxMm = newSettings.writingMaxMm
                        this.fingerMaxMm = newSettings.fingerMaxMm
                        this.relaxedPalmMm = newSettings.relaxedPalmMm
                        this.writingHoldoffMs = newSettings.writingHoldoffMs
                        this.palmProximityMm = newSettings.palmProximityMm
                        this.smoothing = newSettings.smoothing
                    }
                }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: PalmRejectionSettings,
    onSettingChange: (PalmRejectionSettings) -> Unit,
) {
    Column(Modifier.padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Writing", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        // Palm Rejection Mode
        Text("Palm Rejection Mode", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PalmRejectionMode.values().forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = settings.mode == mode,
                    onClick = { onSettingChange(settings.copy(mode = mode)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = PalmRejectionMode.values().size)
                ) { Text(mode.name) }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Sensitivity
        Text("Palm Rejection Sensitivity: ${(settings.sensitivity * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
        androidx.compose.material3.Slider(
            value = settings.sensitivity,
            onValueChange = { v -> onSettingChange(settings.copy(sensitivity = v)) },
            valueRange = 0f..1f,
            steps = 10,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Text("Calibration", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text("Use the Palm Rejection Test screen to calibrate. Calibration values:", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        settings.calibration?.let { cal ->
            Column(Modifier.fillMaxWidth()) {
                cal.fingerMaxDimMm?.let { Text("Finger max: ${"%.1f".format(it)} mm") }
                cal.penMaxDimMm?.let { Text("Pen max: ${"%.1f".format(it)} mm") }
                cal.palmMaxDimMm?.let { Text("Palm max: ${"%.1f".format(it)} mm") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Stroke Smoothing", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text("Smoothing Level", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SmoothingMode.values().forEachIndexed { index, sm ->
                SegmentedButton(
                    selected = settings.smoothing == sm,
                    onClick = { onSettingChange(settings.copy(smoothing = sm)) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = SmoothingMode.values().size)
                ) { Text(sm.name) }
            }
        }
    }
}