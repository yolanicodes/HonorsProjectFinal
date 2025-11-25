package com.example.honoursproject.presentation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.honoursproject.dataservices.DataStreamService
import com.example.honoursproject.R
import com.example.honoursproject.dataservices.EvaluationState
import com.example.honoursproject.dataservices.HealthDataStreamManager
import com.example.honoursproject.dataservices.SimulationCoordinator
import com.example.honoursproject.dataservices.SimulationManager
import com.example.honoursproject.presentation.theme.HonoursProjectTheme
import com.example.honoursproject.presentation.theme.backgroundBlue
import com.example.honoursproject.presentation.theme.follicleGreen
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

// --- Enhanced Navigation Routes ---
object NavigationRoutes {
    const val AGE_PICKER = "age_picker"
    const val STEP_GOAL_PICKER = "step_goal_picker"
    const val START_SIMULATION = "start_simulation"
    const val LOADING_HISTORICAL = "loading_historical"
    const val SIMULATION_RUNNING = "simulation_running"
    const val SIMULATION_STOPPED = "simulation_stopped"
    const val EDIT_STEP_GOAL = "edit_step_goal"
}

// --- Enhanced ViewModel with Loading State Management ---
class MainViewModel(private val activity: ComponentActivity) : ViewModel() {

    // Loading states
    private val _historicalLoadingProgress = MutableStateFlow(0f)
    val historicalLoadingProgress: StateFlow<Float> = _historicalLoadingProgress.asStateFlow()

    private val _historicalLoadingStatus = MutableStateFlow("")
    val historicalLoadingStatus: StateFlow<String> = _historicalLoadingStatus.asStateFlow()

    private val _isHistoricalLoadingComplete = MutableStateFlow(false)
    val isHistoricalLoadingComplete: StateFlow<Boolean> = _isHistoricalLoadingComplete.asStateFlow()

    // Setup states
    private val _isSetupComplete = MutableStateFlow(false)
    val isSetupComplete: StateFlow<Boolean> = _isSetupComplete.asStateFlow()

    private val _selectedAge = MutableStateFlow(25)
    val selectedAge: StateFlow<Int> = _selectedAge.asStateFlow()

    private val _selectedStepGoal = MutableStateFlow(10000)
    val selectedStepGoal: StateFlow<Int> = _selectedStepGoal.asStateFlow()

    private val _isEvaluationMode = MutableStateFlow(false)
    val isEvaluationMode: StateFlow<Boolean> = _isEvaluationMode.asStateFlow()

    // Simulation states
    private val _isSimulationRunning = MutableStateFlow(false)
    val isSimulationRunning: StateFlow<Boolean> = _isSimulationRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _simulationProgress = MutableStateFlow(0f)
    val simulationProgress: StateFlow<Float> = _simulationProgress.asStateFlow()

    private val _synchronizationStatus = MutableStateFlow("Ready")
    val synchronizationStatus: StateFlow<String> = _synchronizationStatus.asStateFlow()

    init {
        viewModelScope.launch {
            loadUserPreferences()
            startSimulationStateMonitoring()
            startHistoricalLoadingMonitoring()
        }
    }

    private suspend fun loadUserPreferences() {
        activity.setupDataStore.data.firstOrNull()?.let { prefs ->
            val isComplete = prefs[SetupPreferences.IS_COMPLETE] ?: false
            _isSetupComplete.value = isComplete

            if (isComplete) {
                _selectedAge.value = prefs[SetupPreferences.AGE] ?: 25
                _selectedStepGoal.value = prefs[SetupPreferences.STEP_GOAL] ?: 10000
            }
        }
    }

    /**
     * Monitor simulation state changes from SimulationCoordinator
     */
    private fun startSimulationStateMonitoring() {
        viewModelScope.launch {
            SimulationCoordinator.combinedSimulationState.collect { combinedState ->
                _isSimulationRunning.value = combinedState.isHealthRunning
                _isPaused.value = combinedState.isHealthPaused
                _simulationProgress.value = combinedState.overallProgress

                _synchronizationStatus.value = if (combinedState.isSynchronized) {
                    "Synchronized"
                } else {
                    SimulationCoordinator.getSynchronizationStatusMessage()
                }

                Log.d("MainViewModel", "Simulation state: Running=${combinedState.isHealthRunning}, " +
                        "Paused=${combinedState.isHealthPaused}, Progress=${combinedState.overallProgress}%")
            }
        }
    }

    /**
     * Monitor historical data loading progress
     */
    private fun startHistoricalLoadingMonitoring() {
        viewModelScope.launch {
            // Monitor loading progress
            launch {
                SimulationManager.historicalLoadingProgress.collect { progress ->
                    _historicalLoadingProgress.value = progress

                    _historicalLoadingStatus.value = when {
                        progress < 0.1f -> "Initializing historical data..."
                        progress < 0.5f -> "Loading historical data (${(progress * 100).toInt()}%)..."
                        progress < 0.9f -> "Processing historical records..."
                        progress < 1.0f -> "Finalizing data load..."
                        else -> "Verifying data accessibility..."
                    }
                }
            }

            // NEW: Monitor data accessibility
            launch {
                SimulationManager.isDataAccessible.collect { isAccessible ->
                    if (isAccessible) {
                        _historicalLoadingStatus.value = "✓ Ready to simulate"
                    }
                }
            }

            // Monitor completion
            launch {
                SimulationManager.isHistoricalLoadingComplete.collect { isComplete ->
                    _isHistoricalLoadingComplete.value = isComplete
                }
            }
        }
    }

    // User interaction functions
    fun onAgeSelected(age: Int) {
        _selectedAge.value = age
    }

    fun onStepGoalSelected(goal: Int) {
        _selectedStepGoal.value = goal
        viewModelScope.launch {
            saveStepGoal(goal)
        }
    }

    private suspend fun saveStepGoal(goal: Int) {
        activity.setupDataStore.edit { prefs ->
            prefs[SetupPreferences.STEP_GOAL] = goal
        }
    }

    fun saveSetupPreferences() {
        viewModelScope.launch {
            activity.setupDataStore.edit { prefs ->
                prefs[SetupPreferences.AGE] = _selectedAge.value
                prefs[SetupPreferences.STEP_GOAL] = _selectedStepGoal.value
                prefs[SetupPreferences.IS_COMPLETE] = true
            }
            _isSetupComplete.value = true
        }
    }

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

    /**
     * Start simulation and initialize loading state
     */
    fun startSimulation() {
        Log.i("MainViewModel", "Starting simulation - Evaluation mode: ${_isEvaluationMode.value}")

        // Reset loading states
        _historicalLoadingProgress.value = 0f
        _historicalLoadingStatus.value = "Initializing..."
        _isHistoricalLoadingComplete.value = false

        // Set evaluation mode
        if (_isEvaluationMode.value) {
            EvaluationState.startEvaluationMode()
        } else {
            EvaluationState.stopEvaluationMode()
        }

        // Start coordinated simulation
        SimulationCoordinator.startSimulation(activity)
    }

    fun togglePauseResume() {
        if (SimulationCoordinator.canPause() && !_isPaused.value) {
            Log.i("MainViewModel", "Pausing simulation")
            SimulationCoordinator.pauseSimulation(activity)
        } else if (SimulationCoordinator.canResume() && _isPaused.value) {
            Log.i("MainViewModel", "Resuming simulation")
            SimulationCoordinator.resumeSimulation(activity)
        }
    }

    fun stopSimulation() {
        Log.i("MainViewModel", "Stopping simulation")
        SimulationCoordinator.stopSimulation(activity)
        EvaluationState.stopEvaluationMode()
        _isEvaluationMode.value = false
    }

    suspend fun resetSimulation() {
        Log.i("MainViewModel", "Resetting simulation")
        SimulationCoordinator.resetSimulation(activity)
        EvaluationState.stopEvaluationMode()
        _isEvaluationMode.value = false
    }

    fun getSimulationStatusMessage(): String {
        return when {
            _isPaused.value -> "Paused (${_simulationProgress.value.toInt()}%)"
            _isSimulationRunning.value -> "Running (${_simulationProgress.value.toInt()}%)"
            SimulationCoordinator.isStopped() -> "Stopped"
            else -> "Ready"
        }
    }
}

// --- MainActivity ---
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
                checkFullScreenIntentPermission()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            checkFullScreenIntentPermission()
        }
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (!notificationManager.canUseFullScreenIntent()) {
                Log.w("MainActivity", "Full screen intent permission not granted")
                // Request permission via settings
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Could not open full screen intent settings", e)
                }
            }
        }

        // Start simulation regardless (fallback mechanisms exist)
        viewModel.startSimulation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            WearApp(
                viewModel = viewModel,
                onStartSimulation = { startSimulationWithPermissionCheck() }
            )
        }
    }
}

// --- Main Composable with Navigation ---
@Composable
fun WearApp(
    viewModel: MainViewModel,
    onStartSimulation: () -> Unit
) {
    val navController = rememberSwipeDismissableNavController()
    val coroutineScope = rememberCoroutineScope()

    val isSetupComplete by viewModel.isSetupComplete.collectAsState()

    // Collect states
    val selectedAge by viewModel.selectedAge.collectAsState()
    val selectedStepGoal by viewModel.selectedStepGoal.collectAsState()
    val isEvaluationMode by viewModel.isEvaluationMode.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isRunning by viewModel.isSimulationRunning.collectAsState()
    val simulationProgress by viewModel.simulationProgress.collectAsState()
    val synchronizationStatus by viewModel.synchronizationStatus.collectAsState()
    val historicalLoadingProgress by viewModel.historicalLoadingProgress.collectAsState()
    val historicalLoadingStatus by viewModel.historicalLoadingStatus.collectAsState()
    val isHistoricalLoadingComplete by viewModel.isHistoricalLoadingComplete.collectAsState()

    HonoursProjectTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBlue)
        ) {
            TimeText()

            SwipeDismissableNavHost(
                navController = navController,
                startDestination = if (isSetupComplete) NavigationRoutes.START_SIMULATION else NavigationRoutes.AGE_PICKER
            ) {
                composable(NavigationRoutes.AGE_PICKER) {
                    AgePickerScreen(
                        selectedAge = selectedAge,
                        onAgeChange = { viewModel.onAgeSelected(it) },
                        onConfirm = {
                            viewModel.onAgeSelected(selectedAge)
                            navController.navigate(NavigationRoutes.STEP_GOAL_PICKER)
                        }
                    )
                }

                composable(NavigationRoutes.STEP_GOAL_PICKER) {
                    StepGoalPickerScreen(
                        selectedStepGoal = selectedStepGoal,
                        onStepGoalChange = { viewModel.onStepGoalSelected(it) },
                        onConfirm = {
                            viewModel.saveSetupPreferences()
                            navController.navigate(NavigationRoutes.START_SIMULATION) {
                                popUpTo(NavigationRoutes.AGE_PICKER) { inclusive = true }
                            }
                        }
                    )
                }

                composable(NavigationRoutes.START_SIMULATION) {
                    StartSimulationScreen(
                        isEvaluationMode = isEvaluationMode,
                        onStart = {
                            onStartSimulation()
                            navController.navigate(NavigationRoutes.LOADING_HISTORICAL) {
                                popUpTo(NavigationRoutes.START_SIMULATION) { inclusive = true }
                            }
                        },
                        onToggleEvaluation = { viewModel.onToggleEvaluationMode(it) }
                    )
                }

                composable(NavigationRoutes.LOADING_HISTORICAL) {
                    HistoricalLoadingScreen(
                        progress = historicalLoadingProgress,
                        statusMessage = historicalLoadingStatus,
                        isComplete = isHistoricalLoadingComplete,
                        onLoadingComplete = {
                            navController.navigate(NavigationRoutes.SIMULATION_RUNNING) {
                                popUpTo(NavigationRoutes.LOADING_HISTORICAL) { inclusive = true }
                            }
                        }
                    )
                }

                composable(NavigationRoutes.SIMULATION_RUNNING) {
                    SimulationRunningScreen(
                        isPaused = isPaused,
                        progress = simulationProgress,
                        synchronizationStatus = synchronizationStatus,
                        statusMessage = viewModel.getSimulationStatusMessage(),
                        onTogglePauseResume = { viewModel.togglePauseResume() },
                        onStop = {
                            viewModel.stopSimulation()
                            navController.navigate(NavigationRoutes.SIMULATION_STOPPED) {
                                popUpTo(NavigationRoutes.SIMULATION_RUNNING) { inclusive = true }
                            }
                        },
                        onEditStepGoal = {
                            navController.navigate(NavigationRoutes.EDIT_STEP_GOAL)
                        }
                    )
                }

                composable(NavigationRoutes.EDIT_STEP_GOAL) {
                    EditStepGoalScreen(
                        currentStepGoal = selectedStepGoal,
                        onStepGoalChange = { viewModel.onStepGoalSelected(it) },
                        onConfirm = {
                            navController.popBackStack()
                        }
                    )
                }

                composable(NavigationRoutes.SIMULATION_STOPPED) {
                    SimulationStoppedScreen(
                        onRestart = {
                            coroutineScope.launch {
                                viewModel.resetSimulation()
                                navController.navigate(NavigationRoutes.START_SIMULATION) {
                                    popUpTo(NavigationRoutes.SIMULATION_STOPPED) { inclusive = true }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

// --- Screen Composables ---

@Composable
fun AgePickerScreen(
    selectedAge: Int,
    onAgeChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    val ageOptions = (1..150).toList()
    val state = rememberPickerState(
        initialNumberOfOptions = ageOptions.size,
        initiallySelectedOption = selectedAge - 1
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
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
}

@Composable
fun StepGoalPickerScreen(
    selectedStepGoal: Int,
    onStepGoalChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    val stepGoalOptions = (0..50000 step 500).toList()
    val initialIndex = stepGoalOptions.indexOf(selectedStepGoal).coerceAtLeast(0)
    val state = rememberPickerState(
        initialNumberOfOptions = stepGoalOptions.size,
        initiallySelectedOption = initialIndex
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
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
                separation = 4.dp
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
}

@Composable
fun StartSimulationScreen(
    isEvaluationMode: Boolean,
    onStart: () -> Unit,
    onToggleEvaluation: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            ToggleChip(
                checked = isEvaluationMode,
                onCheckedChange = onToggleEvaluation,
                label = { Text("Evaluation Mode") },
                modifier = Modifier.fillMaxWidth(),
                toggleControl = {
                    Switch(
                        checked = isEvaluationMode,
                        modifier = Modifier.semantics {
                            this.contentDescription = if (isEvaluationMode) "On" else "Off"
                        }
                    )
                },
                secondaryLabel = {
                    Text(if (isEvaluationMode) "Historical (Aug 2023)" else "Current date")
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onStart,
                modifier = Modifier.width(120.dp)
            ) {
                Text("Start")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isEvaluationMode) {
                    "Will load historical data from August 2023"
                } else {
                    "Will use current date and time"
                },
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
        }
    }
}

@Composable
fun HistoricalLoadingScreen(
    progress: Float,
    statusMessage: String,
    isComplete: Boolean,
    onLoadingComplete: () -> Unit
) {
    val isDataAccessible by SimulationManager.isDataAccessible.collectAsState()

    // Only navigate when BOTH complete AND accessible
    LaunchedEffect(isComplete, isDataAccessible) {
        if (isComplete && isDataAccessible) {
            // Extra safety delay
            delay(500)

            // Final verification
            val snapshot = HealthDataStreamManager.getCurrentSnapshot()
            if (snapshot.cumulativeSteps > 0 || snapshot.heartRate > 0) {
                delay(1000) // Give UI components time to initialize
                onLoadingComplete()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                progress = if (isComplete && !isDataAccessible) 0.95f else progress,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isComplete && !isDataAccessible) {
                    "Verifying data accessibility..."
                } else {
                    statusMessage
                },
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isComplete && !isDataAccessible) {
                    "95%"
                } else {
                    "${(progress * 100).toInt()}%"
                },
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center
            )

            if (isComplete && isDataAccessible) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "✓ Data Ready",
                    style = MaterialTheme.typography.caption1,
                    color = follicleGreen,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun SimulationRunningScreen(
    isPaused: Boolean,
    progress: Float,
    synchronizationStatus: String,
    statusMessage: String,
    onTogglePauseResume: () -> Unit,
    onStop: () -> Unit,
    onEditStepGoal: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue)
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            item {
                if (progress > 0) {
                    CircularProgressIndicator(
                        progress = progress / 100f,
                        modifier = Modifier.size(40.dp).padding(vertical = 8.dp)
                    )
                }
            }

            item {
                Text(
                    synchronizationStatus,
                    style = MaterialTheme.typography.caption2,
                    color = if (synchronizationStatus == "Synchronized") {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.error
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    Button(
                        onClick = onTogglePauseResume,
                        modifier = Modifier.size(50.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (isPaused) R.drawable.play_icon else R.drawable.pause_icon
                            ),
                            contentDescription = if (isPaused) "Resume" else "Pause"
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onStop,
                        modifier = Modifier.size(50.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = MaterialTheme.colors.error
                        )
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.stop_icon),
                            contentDescription = "Stop"
                        )
                    }
                }
            }

            item {
                Chip(
                    onClick = onEditStepGoal,
                    label = { Text("Edit Step Goal") },
                    modifier = Modifier.fillMaxWidth(0.9f).padding(vertical = 8.dp),
                    colors = ChipDefaults.chipColors(
                        backgroundColor = MaterialTheme.colors.surface
                    )
                )
            }
        }
    }
}

@Composable
fun EditStepGoalScreen(
    currentStepGoal: Int,
    onStepGoalChange: (Int) -> Unit,
    onConfirm: () -> Unit
) {
    val stepGoalOptions = (0..50000 step 500).toList()
    val initialIndex = stepGoalOptions.indexOf(currentStepGoal).coerceAtLeast(0)
    val state = rememberPickerState(
        initialNumberOfOptions = stepGoalOptions.size,
        initiallySelectedOption = initialIndex
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Edit Step Goal", style = MaterialTheme.typography.title3)
            Spacer(modifier = Modifier.height(16.dp))
            Picker(
                state = state,
                modifier = Modifier.weight(1f),
                separation = 4.dp
            ) {
                Text(stepGoalOptions[it].toString())
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                onStepGoalChange(stepGoalOptions[state.selectedOption])
                onConfirm()
            }) {
                Text("Save")
            }
        }
    }
}

@Composable
fun SimulationStoppedScreen(
    onRestart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBlue),
        contentAlignment = Alignment.Center
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
                "All health data has been processed successfully.",
                style = MaterialTheme.typography.body1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRestart) {
                Text("New Session")
            }
        }
    }
}