package com.example.honoursproject.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.*
import com.example.honoursproject.dataservices.DataStreamService
import com.example.honoursproject.presentation.theme.HonoursProjectTheme
import com.example.honoursproject.presentation.theme.backgroundBlue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

// --- DataStore setup ---
val ComponentActivity.setupDataStore by preferencesDataStore(name = "setup_prefs")

object SetupPreferences {
    val IS_COMPLETE = booleanPreferencesKey("is_setup_complete")
    val AGE = intPreferencesKey("selected_age")
    val STEP_GOAL = intPreferencesKey("selected_step_goal")
}

// --- ViewModel ---
class MainViewModel(private val activity: ComponentActivity) : ViewModel() {

    enum class SetupScreen {
        AgePicker,
        StepGoalPicker,
        StartSimulation
    }
    val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentScreen = MutableStateFlow(SetupScreen.AgePicker)
    val currentScreen: StateFlow<SetupScreen> = _currentScreen.asStateFlow()

    private val _selectedAge = MutableStateFlow(25)
    val selectedAge: StateFlow<Int> = _selectedAge.asStateFlow()

    private val _selectedStepGoal = MutableStateFlow(10000)
    val selectedStepGoal: StateFlow<Int> = _selectedStepGoal.asStateFlow()

    init {
        viewModelScope.launch {
            activity.setupDataStore.data.firstOrNull()?.let { prefs ->
                if (prefs[SetupPreferences.IS_COMPLETE] == true) {
                    _selectedAge.value = prefs[SetupPreferences.AGE] ?: 25
                    _selectedStepGoal.value = prefs[SetupPreferences.STEP_GOAL] ?: 10000
                    _currentScreen.value = SetupScreen.StartSimulation
                }
            }
        }
    }

    fun onAgeSelected(age: Int) {
        _selectedAge.value = age
    }

    fun onStepGoalSelected(goal: Int) {
        _selectedStepGoal.value = goal
    }

    fun proceedToStepGoal() {
        _currentScreen.value = SetupScreen.StepGoalPicker
    }

    fun proceedToStartSimulation() {
        viewModelScope.launch {
            activity.setupDataStore.edit { prefs ->
                prefs[SetupPreferences.AGE] = _selectedAge.value
                prefs[SetupPreferences.STEP_GOAL] = _selectedStepGoal.value
                prefs[SetupPreferences.IS_COMPLETE] = true
            }
        }
        _currentScreen.value = SetupScreen.StartSimulation
    }

}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(this@MainActivity) as T
            }
        }

    }
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // Permission granted, start the service
                startForegroundService()
            }
        }

    private fun startSimulationWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is already granted, start the service
                startForegroundService()
            } else {
                // Permission not granted, request it from the user
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // For older Android versions, no runtime permission is needed
            startForegroundService()
        }
    }

    private fun startForegroundService() {
        val serviceIntent = Intent(this, DataStreamService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        viewModel._isLoading.value = false
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            val currentScreen by viewModel.currentScreen.collectAsState()
            val selectedAge by viewModel.selectedAge.collectAsState()
            val selectedStepGoal by viewModel.selectedStepGoal.collectAsState()
            val scope = rememberCoroutineScope()
            WearApp(
                currentScreen = currentScreen,
                selectedAge = selectedAge,
                selectedStepGoal = selectedStepGoal,
                onAgeChange = { viewModel.onAgeSelected(it) },
                onStepGoalChange = { viewModel.onStepGoalSelected(it) },
                onAgeConfirmed = { viewModel.proceedToStepGoal() },
                onStepGoalConfirmed = { viewModel.proceedToStartSimulation() },
                onStartSimulation = { scope.launch {
                    startSimulationWithPermissionCheck()
                } }
            )
        }
    }
}
@Composable
fun WearApp(
    currentScreen: MainViewModel.SetupScreen,
    selectedAge: Int,
    selectedStepGoal: Int,
    onAgeChange: (Int) -> Unit,
    onStepGoalChange: (Int) -> Unit,
    onAgeConfirmed: () -> Unit,
    onStepGoalConfirmed: () -> Unit,
    onStartSimulation: () -> Unit

) {
    HonoursProjectTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBlue),
            contentAlignment = Alignment.Center
        ) {
            TimeText()
            when (currentScreen) {
                MainViewModel.SetupScreen.AgePicker -> AgePickerScreen(
                    selectedAge = selectedAge,
                    onAgeChange = onAgeChange,
                    onConfirm = onAgeConfirmed
                )
                MainViewModel.SetupScreen.StepGoalPicker -> StepGoalPickerScreen(
                    selectedStepGoal = selectedStepGoal,
                    onStepGoalChange = onStepGoalChange,
                    onConfirm = onStepGoalConfirmed
                )
                MainViewModel.SetupScreen.StartSimulation -> StartSimulationScreen(
                    onStart = onStartSimulation,
                    isLoading = false // Pass a default or observed state here
                )
            }
        }
    }
}

@Composable
fun AgePickerScreen(
    selectedAge: Int,
    onAgeChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    val ageOptions = (1..150).toList()
    val state = rememberPickerState(initialNumberOfOptions = ageOptions.size, initiallySelectedOption = selectedAge - 1)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Select Your Age", style = MaterialTheme.typography.title3)
        Spacer(modifier = Modifier.height(16.dp))
        Picker(
            state = state,
            modifier = Modifier.weight(1f),
            separation = 4.dp
        ) {
            Text(ageOptions[it].toString())
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            onAgeChange(ageOptions[state.selectedOption])
            onConfirm()
        }) {
            Text("Next")
        }
    }
}

@Composable
fun StepGoalPickerScreen(
    selectedStepGoal: Int,
    onStepGoalChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    val stepGoalOptions = (0..50000 step 500).toList()
    val initialIndex = stepGoalOptions.indexOf(selectedStepGoal).coerceAtLeast(0)
    val state = rememberPickerState(initialNumberOfOptions = stepGoalOptions.size, initiallySelectedOption = initialIndex)

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Set Step Goal", style = MaterialTheme.typography.title3)
        Spacer(modifier = Modifier.height(16.dp))
        Picker(
            state = state,
            modifier = Modifier.weight(1f),
            separation = 4.dp,
        ) {
            Text(stepGoalOptions[it].toString())
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            onStepGoalChange(stepGoalOptions[state.selectedOption])
            onConfirm()
        }) {
            Text("Next")
        }
    }
}

@Composable
fun StartSimulationScreen(
    onStart: () -> Unit,
    isLoading: Boolean // Added isLoading parameter
) {
    Column(
        modifier = Modifier.fillMaxSize().padding( 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            // Show a progress indicator while the simulation is preparing
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Preparing simulation...", textAlign = TextAlign.Center)
        } else {
            Text("Setup Complete!")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onStart) {
                Text("Start Simulation")
            }
        }
    }
}

// --- Previews ---

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun AgePickerPreview() {
    AgePickerScreen(selectedAge = 25, onAgeChange = {}, onConfirm = {})
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun StepGoalPickerPreview() {
    StepGoalPickerScreen(selectedStepGoal = 10000, onStepGoalChange = {}, onConfirm = {})
}

@Preview(device = "id:wearos_small_round", showSystemUi = true)
@Composable
fun StartSimulationPreview() {
    StartSimulationScreen(onStart = {}, isLoading = false)
}
