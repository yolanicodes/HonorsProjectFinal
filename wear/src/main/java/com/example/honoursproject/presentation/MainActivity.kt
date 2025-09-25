package com.example.honoursproject.presentation

import android.Manifest
import android.content.Context
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
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
import com.example.honoursproject.dataservices.SimulationManager
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

// --- ViewModel ---
class MainViewModel(private val activity: ComponentActivity) : ViewModel() {

    enum class SetupScreen {
        AgePicker,
        StepGoalPicker,
        StartSimulation,
        SimulationRunning
    }
    val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _currentScreen = MutableStateFlow(SetupScreen.AgePicker)
    val currentScreen: StateFlow<SetupScreen> = _currentScreen.asStateFlow()

    private val _selectedAge = MutableStateFlow(25)
    val selectedAge: StateFlow<Int> = _selectedAge.asStateFlow()

    private val _selectedStepGoal = MutableStateFlow(10000)
    val selectedStepGoal: StateFlow<Int> = _selectedStepGoal.asStateFlow()
    private val _isEvaluationMode = MutableStateFlow(false)
    val isEvaluationMode: StateFlow<Boolean> = _isEvaluationMode.asStateFlow()

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
    fun onToggleEvaluationMode(isEvaluation: Boolean) {
        _isEvaluationMode.value = isEvaluation
        if (isEvaluation) {
            EvaluationState.startEvaluationMode()
        } else {
            // Assuming you add a function to reset the state
            // EvaluationState.stopEvaluationMode()
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
    fun onSimulationStarted() {
        _currentScreen.value = SetupScreen.SimulationRunning
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
        //viewModel._isLoading.value = false
        viewModel.onSimulationStarted()
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
            val scope = rememberCoroutineScope()
            WearApp(
                currentScreen = currentScreen,
                selectedAge = selectedAge,
                selectedStepGoal = selectedStepGoal,
                isEvaluationMode = isEvaluationMode,
                onAgeChange = { viewModel.onAgeSelected(it) },
                onStepGoalChange = { viewModel.onStepGoalSelected(it) },
                onAgeConfirmed = { viewModel.proceedToStepGoal() },
                onStepGoalConfirmed = { viewModel.proceedToStartSimulation() },
                onStartSimulation = { scope.launch {
                    startSimulationWithPermissionCheck()
                } },
                onToggleEvaluation = { viewModel.onToggleEvaluationMode(it) },
                onStopSimulation = {
                    // Stop the foreground service and SimulationManager
                }
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
    onStartSimulation: () -> Unit,
    onToggleEvaluation: (Boolean) -> Unit,
    onStopSimulation: () -> Unit,
    isEvaluationMode: Boolean
) {
    HonoursProjectTheme {
        val context= LocalContext.current
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

                MainViewModel.SetupScreen.SimulationRunning -> SimulationRunningScreen(onStop = onStopSimulation,
                    onPause = { SimulationManager.pauseSimulation(
                        context = context
                    ) })
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
        modifier = Modifier.fillMaxSize().padding( 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
//        Text("Ready To Start", style = MaterialTheme.typography.title2)
        Spacer(modifier = Modifier.height(16.dp))

        // Replace the second button with a ToggleChip
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
            secondaryLabel =  { Text("Activate Evaluation Mode") },
            colors = ToggleChipDefaults.toggleChipColors(
                checkedEndBackgroundColor = MaterialTheme.colors.primaryVariant,
                uncheckedEndBackgroundColor = MaterialTheme.colors.primaryVariant,
                uncheckedContentColor = MaterialTheme.colors.onPrimary,
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Single "Start" button
        Button(onClick = onStart) {
            Text("Start")
        }
        
    }
}
@Composable
fun SimulationRunningScreen(
    onPause: () -> Unit,
    onStop: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var isPaused by remember { mutableStateOf(false) }

    // This effect will switch from the loading indicator to the success message
    LaunchedEffect(key1 = Unit) {
        delay(2500) // Wait 2.5 seconds to simulate setup
        isLoading = false
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Starting Simulation...")
        } else {
            Text(
                "Simulation Started!",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pause/Resume Button
                Button(
                    onClick = {
                        isPaused = !isPaused
                        onPause() // This should call a function to pause/resume the manager
                    },
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



