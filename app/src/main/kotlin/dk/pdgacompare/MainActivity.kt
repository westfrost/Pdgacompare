package dk.pdgacompare

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dk.pdgacompare.ui.HomeScreen
import dk.pdgacompare.ui.LayoutDetailScreen
import dk.pdgacompare.ui.NewLayoutScreen
import dk.pdgacompare.ui.NewRoundScreen
import dk.pdgacompare.ui.RatingCheckScreen
import dk.pdgacompare.ui.ScoringScreen
import dk.pdgacompare.ui.SummaryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppRoot(viewModel())
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun AppRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        vm.message = null
        snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
    }
    BackHandler(enabled = vm.backStack.size > 1) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        when (val screen = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.NewLayout -> NewLayoutScreen(vm)
            is Screen.LayoutDetail -> LayoutDetailScreen(vm, screen.layoutId)
            is Screen.NewRound -> NewRoundScreen(vm, screen.layoutId)
            is Screen.Scoring -> ScoringScreen(vm, screen.roundId)
            is Screen.Summary -> SummaryScreen(vm, screen.roundId)
            is Screen.RatingCheck -> RatingCheckScreen(vm, screen.layoutId)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
