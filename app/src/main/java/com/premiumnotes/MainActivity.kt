package com.premiumnotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.premiumnotes.ui.diagnostics.DiagnosticsScreen
import com.premiumnotes.ui.editor.EditorScreen
import com.premiumnotes.ui.home.HomeScreen
import com.premiumnotes.ui.theme.PremiumNotesTheme

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
                palmRejectionEngine = container.palmRejectionEngine,
                palmRejectionSettings = container.palmRejectionSettings,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(
                engine = container.palmRejectionEngine,
                capabilities = container.inputCapabilities,
                settings = container.palmRejectionSettings,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreenPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenPlaceholder(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding))
    }
}