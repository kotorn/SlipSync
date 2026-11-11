package com.thaweekarn.slipsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thaweekarn.slipsync.data.preferences.SlipPreferencesRepository
import com.thaweekarn.slipsync.ui.screens.HealthCheckScreen
import com.thaweekarn.slipsync.ui.screens.HomeScreen
import com.thaweekarn.slipsync.ui.screens.LoginScreen
import com.thaweekarn.slipsync.ui.screens.OnboardingScreen
import com.thaweekarn.slipsync.ui.screens.SettingsScreen
import com.thaweekarn.slipsync.ui.screens.SlipDetailScreen
import com.thaweekarn.slipsync.ui.theme.SlipSyncTheme
import com.thaweekarn.slipsync.ui.viewmodel.DetailViewModel
import com.thaweekarn.slipsync.ui.viewmodel.HomeViewModel
import com.thaweekarn.slipsync.ui.viewmodel.LoginViewModel
import com.thaweekarn.slipsync.ui.viewmodel.OnboardingViewModel
import com.thaweekarn.slipsync.ui.viewmodel.SettingsViewModel

sealed class Screen {
    data object Onboarding : Screen()
    data object Login : Screen()
    data object Home : Screen()
    data class Detail(val sha256: String) : Screen()
    data object Settings : Screen()
    data object HealthCheck : Screen()
}

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels()
    private val onboardingViewModel: OnboardingViewModel by viewModels()
    private val detailViewModel: DetailViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val loginViewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SlipSyncTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SlipSyncApp(
                        homeViewModel = homeViewModel,
                        onboardingViewModel = onboardingViewModel,
                        detailViewModel = detailViewModel,
                        settingsViewModel = settingsViewModel,
                        loginViewModel = loginViewModel
                    )
                }
            }
        }
    }
}

@Composable
fun SlipSyncApp(
    homeViewModel: HomeViewModel,
    onboardingViewModel: OnboardingViewModel,
    detailViewModel: DetailViewModel,
    settingsViewModel: SettingsViewModel,
    loginViewModel: LoginViewModel
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { SlipPreferencesRepository(context) }
    val onboardingCompleted by prefs.onboardingCompleted.collectAsStateWithLifecycle(initialValue = null)

    var currentScreen by remember { mutableStateOf<Screen?>(null) }

    LaunchedEffect(onboardingCompleted) {
        if (currentScreen == null && onboardingCompleted != null) {
            currentScreen = if (onboardingCompleted == true) Screen.Home else Screen.Onboarding
        }
    }

    when (val screen = currentScreen) {
        is Screen.Onboarding -> {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onFinished = { currentScreen = Screen.Home }
            )
        }
        is Screen.Login -> {
            BackHandler {
                currentScreen = Screen.Settings
            }
            LoginScreen(
                viewModel = loginViewModel,
                onContinue = { currentScreen = Screen.Home },
                onBack = { currentScreen = Screen.Settings }
            )
        }
        is Screen.Home -> {
            HomeScreen(
                viewModel = homeViewModel,
                onNavigateToSettings = { currentScreen = Screen.Settings },
                onNavigateToDetail = { sha256 -> currentScreen = Screen.Detail(sha256) }
            )
        }
        is Screen.Detail -> {
            BackHandler {
                currentScreen = Screen.Home
            }
            SlipDetailScreen(
                sha256 = screen.sha256,
                viewModel = detailViewModel,
                onBack = { currentScreen = Screen.Home }
            )
        }
        is Screen.Settings -> {
            BackHandler {
                currentScreen = Screen.Home
            }
            SettingsScreen(
                viewModel = settingsViewModel,
                onNavigateToHealthCheck = { currentScreen = Screen.HealthCheck },
                onNavigateToLogin = { currentScreen = Screen.Login },
                onBack = { currentScreen = Screen.Home }
            )
        }
        is Screen.HealthCheck -> {
            BackHandler {
                currentScreen = Screen.Settings
            }
            HealthCheckScreen(
                viewModel = settingsViewModel,
                onNavigateToSettings = { currentScreen = Screen.Settings },
                onBack = { currentScreen = Screen.Settings }
            )
        }
        null -> {
            // Loading initial preference state
        }
    }
}
