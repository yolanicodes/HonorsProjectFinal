package com.example.honoursproject.presentation

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material.*
import com.example.honoursproject.dataservices.DataStreamService
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.EvaluationState
import com.example.honoursproject.dataservices.SimulationCoordinator
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.dataservices.WorkoutSimulationManager
import com.example.honoursproject.presentation.theme.HonoursProjectTheme
import com.example.honoursproject.presentation.theme.backgroundBlue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

// --- DataStore setup ---
val Context.setupDataStore by preferencesDataStore(name = "setup_prefs")

object SetupPreferences {
    val IS_COMPLETE = booleanPreferencesKey("is_setup_complete")
    val AGE = intPreferencesKey("selected_age")
    val STEP_GOAL = intPreferencesKey("selected_step_goal")
}

// --- Enhanced ViewModel with SimulationCoordinator integration ---
class MainViewModel(private val activity: ComponentActivity) : ViewModel() {

    enum class SetupScreen {
        AgePicker,
        StepGoalPicker,
        SimulationRunning,
        StartSimulation,
        SimulationPaused,
        SimulationStopped
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentScreen = MutableStateFlow(SetupScreen.AgePicker)
    val currentScreen: StateFlow<SetupScreen> = _currentScreen.asStateFlow()

    private val _selectedAge = MutableStateFlow(25)
    val selectedAge: StateFlow<Int> = _selectedAge.asStateFlow()

    private val _selectedStepGoal = MutableStateFlow(10000)
    val selectedStepGoal: StateFlow<Int> = _selectedStepGoal.asStateFlow()

    private val _isEvaluationMode = MutableStateFlow(false)
    val isEvaluationMode: StateFlow<Boolean> = _isEvaluationMode.asStateFlow()

    private val _isSimulationRunning = MutableStateFlow(false)
    val isSimulationRunning: StateFlow<Boolean> = _isSimulationRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isSimulationComplete = MutableStateFlow(false)
    val isSimulationComplete: StateFlow<Boolean> = _isSimulationComplete.asStateFlow()
    private val _evaluationLoadingStatus = MutableStateFlow("")
    val evaluationLoadingStatus: StateFlow<String> = _evaluationLoadingStatus.asStateFlow()

    private val _isStartingUp = MutableStateFlow(false)
    val isStartingUp: StateFlow<Boolean> = _isStartingUp.asStateFlow()
    // Enhanced state tracking with SimulationCoordinator
    private val _simulationProgress = MutableStateFlow(0f)
    val simulationProgress: StateFlow<Float> = _simulationProgress.asStateFlow()

    private val _synchronizationStatus = MutableStateFlow("Ready")
    val synchronizationStatus: StateFlow<String> = _synchronizationStatus.asStateFlow()

    init {
        viewModelScope.launch {
            activity.setupDataStore.data.firstOrNull()?.let { prefs ->
                if (prefs[SetupPreferences.IS_COMPLETE] == true) {
                    _selectedAge.value = prefs[SetupPreferences.AGE] ?: 25
                    _selectedStepGoal.value = prefs[SetupPreferences.STEP_GOAL] ?: 10000
                    _currentScreen.value = SetupScreen.StartSimulation
                }
            }
            _isLoading.value = false

            // Start monitoring simulation state changes
            startSimulationStateMonitoring()
        }
    }

    /**
     * Monitor simulation state changes from SimulationCoordinator
     */
    private fun startSimulationStateMonitoring() {
        viewModelScope.launch {
            SimulationCoordinator.combinedSimulationState.collect { combinedState ->
                // Update local state based on combined simulation state
                _isSimulationRunning.value = combinedState.isHealthRunning
                _isPaused.value = combinedState.isHealthPaused
                _simulationProgress.value = combinedState.overallProgress

                // Update synchronization status
                _synchronizationStatus.value = if (combinedState.isSynchronized) {
                    "Synchronized"
                } else {
                    SimulationCoordinator.getSynchronizationStatusMessage()
                }

                // Update screen based on simulation state
                if (combinedState.isHealthRunning) {
                    if (combinedState.isHealthPaused) {
                        _currentScreen.value = SetupScreen.SimulationPaused
                    } else {
                        _currentScreen.value = SetupScreen.SimulationRunning
                    }
                } else if (_isSimulationRunning.value) {
                    // Was running but now stopped
                    _currentScreen.value = SetupScreen.SimulationStopped
                }

                Log.d("MainViewModel", "Simulation state updated: Running=${combinedState.isHealthRunning}, " +
                        "Paused=${combinedState.isHealthPaused}, Progress=${combinedState.overallProgress}%, " +
                        "Synchronized=${combinedState.isSynchronized}")
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

    /**
     * Enhanced evaluation mode toggle with proper state management
     */
    fun onToggleEvaluationMode(isEvaluation: Boolean) {
        _isEvaluationMode.value = isEvaluation
        if (isEvaluation) {
            Log.i("MainViewModel", "Enabling evaluation mode")
            EvaluationState.startEvaluationMode()
        } else {
            Log.i("MainViewModel", "Disabling evaluation mode")
            EvaluationState.stopEvaluationMode()
        }
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

    /**
     * Enhanced simulation start with coordinated services
     */
    fun startSimulation() {
        Log.i("MainViewModel", "Starting simulation - Evaluation mode: ${_isEvaluationMode.value}")

        _isStartingUp.value = true

        // Ensure evaluation state is properly set before starting
        if (_isEvaluationMode.value) {
            EvaluationState.startEvaluationMode()
            _evaluationLoadingStatus.value = "Activating evaluation mode..."
            Log.i("MainViewModel", "Evaluation mode activated for simulation")
        } else {
            EvaluationState.stopEvaluationMode()
            _evaluationLoadingStatus.value = "Starting simulation..."
            Log.i("MainViewModel", "Normal mode activated for simulation")
        }

        // Use SimulationCoordinator to start all services
        SimulationCoordinator.startSimulation(activity)

        // Monitor startup progress
        viewModelScope.launch {
            delay(2000) // Give services time to start

            if (_isEvaluationMode.value) {
                _evaluationLoadingStatus.value = "Loading historical data (Aug 2023)..."
                delay(3000) // Extra time for historical data loading
                _evaluationLoadingStatus.value = "Initializing evaluation timeline..."
            } else {
                _evaluationLoadingStatus.value = "Initializing simulation..."
            }

            delay(2000)
            _evaluationLoadingStatus.value = "Ready"
            _isStartingUp.value = false
        }

        // State will be updated automatically through the monitoring flow
        Log.i("MainViewModel", "Simulation start command sent")
    }



    /**
     * Enhanced pause/resume with SimulationCoordinator
     */
    fun togglePauseResume() {
        if (SimulationCoordinator.canPause() && !_isPaused.value) {
            Log.i("MainViewModel", "Pausing simulation")
            SimulationCoordinator.pauseSimulation(activity)
        } else if (SimulationCoordinator.canResume() && _isPaused.value) {
            Log.i("MainViewModel", "Resuming simulation")
            SimulationCoordinator.resumeSimulation(activity)
        }
    }

    /**
     * Enhanced stop with proper cleanup
     */
    fun stopSimulation() {
        Log.i("MainViewModel", "Stopping simulation")
        SimulationCoordinator.stopSimulation(activity)

        // Reset evaluation mode when stopping
        EvaluationState.stopEvaluationMode()
        _isEvaluationMode.value = false
    }

    /**
     * Reset simulation to initial state
     */
    fun resetSimulation() {
        Log.i("MainViewModel", "Resetting simulation")
        SimulationCoordinator.resetSimulation(activity)

        // Reset evaluation mode
        EvaluationState.stopEvaluationMode()
        _isEvaluationMode.value = false
    }

    /**
     * Restart from setup screen
     */
    fun restartFromSetup() {
        _currentScreen.value = SetupScreen.StartSimulation
        _isEvaluationMode.value = false
        EvaluationState.stopEvaluationMode()
    }

    /**
     * Get current simulation status message
     */
    fun getSimulationStatusMessage(): String {
        return when {
            _isPaused.value -> "Simulation Paused (${_simulationProgress.value.toInt()}%)"
            _isSimulationRunning.value -> "Simulation Running (${_simulationProgress.value.toInt()}%)"
            SimulationCoordinator.isStopped() -> "Simulation Stopped"
            else -> "Ready to Start"
        }
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return MainViewModel(this@MainActivity) as T
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                viewModel.startSimulation()
            } else {
                Log.w("MainActivity", "Notification permission denied")
                // Still try to start the simulation even without notification permission
                viewModel.startSimulation()
            }
        }

    private fun startSimulationWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is already granted, start the simulation
                viewModel.startSimulation()
            } else {
                // Permission not granted, request it from the user
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // For older Android versions, no runtime permission is needed
            viewModel.startSimulation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            val currentScreen by viewModel.currentScreen.collectAsState()
            val selectedAge by viewModel.selectedAge.collectAsState()
            val selectedStepGoal by viewModel.selectedStepGoal.collectAsState()
            val isEvaluationMode by viewModel.isEvaluationMode.collectAsState()
            val isPaused by viewModel.isPaused.collectAsState()
            val isRunning by viewModel.isSimulationRunning.collectAsState()
            val simulationProgress by viewModel.simulationProgress.collectAsState()
            val synchronizationStatus by viewModel.synchronizationStatus.collectAsState()
            val evaluationLoadingStatus by viewModel.evaluationLoadingStatus.collectAsState()  // Add this
            val isStartingUp by viewModel.isStartingUp.collectAsState()
            WearApp(
                currentScreen = currentScreen,
                selectedAge = selectedAge,
                selectedStepGoal = selectedStepGoal,
                isEvaluationMode = isEvaluationMode,
                isPaused = isPaused,
                isRunning = isRunning,
                simulationProgress = simulationProgress,
                synchronizationStatus = synchronizationStatus,
                onAgeChange = { viewModel.onAgeSelected(it) },
                onStepGoalChange = { viewModel.onStepGoalSelected(it) },
                onAgeConfirmed = { viewModel.proceedToStepGoal() },
                onStepGoalConfirmed = { viewModel.proceedToStartSimulation() },
                onStartSimulation = { startSimulationWithPermissionCheck() },
                onToggleEvaluation = { viewModel.onToggleEvaluationMode(it) },
                onTogglePauseResume = { viewModel.togglePauseResume() },
                onStopSimulation = { viewModel.stopSimulation() },
                onRestartFromSetup = { viewModel.restartFromSetup() },
                statusMessage = viewModel.getSimulationStatusMessage(),
                evaluationLoadingStatus =evaluationLoadingStatus,
                isStartingUp = isStartingUp
            )
        }
    }
}

@Composable
fun WearApp(
    currentScreen: MainViewModel.SetupScreen,
    selectedAge: Int,
    selectedStepGoal: Int,
    isEvaluationMode: Boolean,
    isPaused: Boolean,
    isRunning: Boolean,
    simulationProgress: Float,
    synchronizationStatus: String,
    statusMessage: String,
    onAgeChange: (Int) -> Unit,
    onStepGoalChange: (Int) -> Unit,
    onAgeConfirmed: () -> Unit,
    onStepGoalConfirmed: () -> Unit,
    onStartSimulation: () -> Unit,
    onToggleEvaluation: (Boolean) -> Unit,
    onTogglePauseResume: () -> Unit,
    onStopSimulation: () -> Unit,
    onRestartFromSetup: () -> Unit,
    evaluationLoadingStatus: String,  // Add this
    isStartingUp: Boolean,
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
                    isEvaluationMode = isEvaluationMode,
                    onStart = onStartSimulation,
                    onToggleEvaluation = onToggleEvaluation
                )
                MainViewModel.SetupScreen.SimulationRunning ->(isRunning)
                MainViewModel.SetupScreen.SimulationPaused -> SimulationRunningScreen(
                isPaused = isPaused,
                progress = simulationProgress,
                synchronizationStatus = synchronizationStatus,
                statusMessage = statusMessage,
                onTogglePauseResume = onTogglePauseResume,
                onStop = onStopSimulation,
                evaluationLoadingStatus = evaluationLoadingStatus,
                isStartingUp = isStartingUp
            )
                MainViewModel.SetupScreen.SimulationStopped -> SimulationStoppedScreen(
                    onRestart = onRestartFromSetup
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
    isEvaluationMode: Boolean,
    onStart: () -> Unit,
    onToggleEvaluation: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Enhanced ToggleChip for evaluation mode
        ToggleChip(
            checked = isEvaluationMode,
            onCheckedChange = onToggleEvaluation,
            label = { Text("Evaluation Mode") },
            modifier = Modifier.fillMaxWidth(),
            toggleControl = {
                Switch(
                    checked = isEvaluationMode,
                    modifier = Modifier.semantics {
                        this.contentDescription = if (isEvaluationMode) "Evaluation mode on" else "Evaluation mode off"
                    }
                )
            },
            secondaryLabel = {
                Text(if (isEvaluationMode) "Using historical data (Aug 2023)" else "Using current date simulation")
            },
            colors = ToggleChipDefaults.toggleChipColors(
                checkedEndBackgroundColor = MaterialTheme.colors.primaryVariant,
                uncheckedEndBackgroundColor = MaterialTheme.colors.surface,
                uncheckedContentColor = MaterialTheme.colors.onSurface,
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Enhanced Start button with mode indicator
        Button(
            onClick = onStart,
            modifier = Modifier.width(60.dp)
        ) {
            Text(if (isEvaluationMode) "Start" else "Start")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Mode description
        Text(
            text = if (isEvaluationMode) {
                "Evaluation mode will simulate data from August 2023"
            } else {
                "Simulation will use current date and time"
            },
            style = MaterialTheme.typography.caption1,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun SimulationRunningScreen(
    isPaused: Boolean,
    progress: Float,
    synchronizationStatus: String,
    statusMessage: String,
    evaluationLoadingStatus: String,
    isStartingUp: Boolean,
    onTogglePauseResume: () -> Unit,
    onStop: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(key1 = Unit) {
        delay(2500)
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading || isStartingUp) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))

            // Show specific loading status
            Text(
                if (evaluationLoadingStatus.isNotBlank() && evaluationLoadingStatus != "Ready") {
                    evaluationLoadingStatus
                } else {
                    "Starting Simulation..."
                },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.body1
            )

            if (evaluationLoadingStatus.contains("historical")) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This may take a moment...",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Status text
            Text(
                statusMessage,
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Progress indicator
            if (progress > 0) {
                CircularProgressIndicator(
                    progress = progress / 100f,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${progress.toInt()}% Complete",
                    style = MaterialTheme.typography.caption1
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Synchronization status
            Text(
                synchronizationStatus,
                style = MaterialTheme.typography.caption2,
                color = if (synchronizationStatus == "Synchronized") {
                    MaterialTheme.colors.primary
                } else {
                    MaterialTheme.colors.error
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Control buttons (only show when not loading)
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pause/Resume Button
                Button(
                    onClick = onTogglePauseResume,
                    modifier = Modifier.size(60.dp),
                    shape = CircleShape
                ) {
                    Icon(
                        painter = painterResource(id = if (isPaused) R.drawable.play_icon else R.drawable.pause_icon),
                        contentDescription = if (isPaused) "Resume" else "Pause"
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                // Stop Button
                Button(
                    onClick = onStop,
                    modifier = Modifier.size(60.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.error)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.stop_icon),
                        contentDescription = "Stop"
                    )
                }
            }
        }
    }
}
@Composable
fun SimulationStoppedScreen(
    onRestart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Simulation Complete",
            style = MaterialTheme.typography.title2,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "The simulation has been completed successfully. All health data has been processed.",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRestart) {
            Text("Start New Session")
        }
    }
}