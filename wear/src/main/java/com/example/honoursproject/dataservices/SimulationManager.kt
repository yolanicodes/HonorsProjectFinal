// Enhanced SimulationManager.kt with improved pause/resume and UI streaming
package com.example.honoursproject.dataservices

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.honoursproject.presentation.SetupPreferences
import com.example.honoursproject.presentation.setupDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.time.LocalDate
import java.time.ZoneId

// Create DataStore delegate on Context
val Context.simulationDataStore by preferencesDataStore(name = "simulation_prefs")

// Enhanced simulation state tracking
data class SimulationState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentIndex: Int = 0,
    val totalRecords: Int = 0,
    val progressPercentage: Float = 0f,
    val lastUpdateTime: Long = 0L
)

object SimulationManager {

    const val MAX_HEART_RATE = 220

    // Enhanced persistence keys for global interruption tracking
    private val KEY_INDEX = intPreferencesKey("csv_index")
    private val KEY_LAST_PAUSE_TIME = longPreferencesKey("last_pause_time")
    private val KEY_TOTAL_PAUSE_DURATION = longPreferencesKey("total_pause_duration")
    private val KEY_SIMULATION_START_TIME = longPreferencesKey("simulation_start_time")
    private val KEY_IS_PAUSED = booleanPreferencesKey("is_paused")
    private val KEY_LAST_RECORD_TIME = longPreferencesKey("last_record_time")
    private val KEY_CUMULATIVE_STEPS = intPreferencesKey("cumulative_steps")
    private val KEY_CUMULATIVE_ENERGY = longPreferencesKey("cumulative_energy") // Store as long (multiplied by 1000)

    // Enhanced state flows for UI updates
    private val _healthDataStream = MutableStateFlow(HealthDataRecord())
    val healthDataStream = _healthDataStream.asStateFlow()

    private val _simulationState = MutableStateFlow(SimulationState())
    val simulationState = _simulationState.asStateFlow()

    private val _synchronizationProgress = MutableStateFlow(0f)
    val synchronizationProgress = _synchronizationProgress.asStateFlow()

    // Global simulation state tracking
    private var simulationJob: Job? = null
    private var synchronizationJob: Job? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var simulationStartTime: Long = 0L
    private var lastPauseTime: Long = 0L
    private var totalPauseDuration: Long = 0L
    private var isPaused: Boolean = false
    private var lastRecordTime: Long = 0L

    // Simulation data state
    private var cumulativeSteps = 0
    private var cumulativeEnergy = 0.0
    private var currentSimulationDay: Int = -1
    private var lastKnownRecord = HealthDataRecord()
    private var currentIndex: Int = 0
    private var dataLines: List<String> = emptyList()
    private var isSimulationComplete: Boolean = false

    // Enhanced UI streaming queue for missed data
    private val pendingUIUpdates = mutableListOf<HealthDataRecord>()
    private var uiStreamingJob: Job? = null

    suspend fun getUserAge(context: Context): Int {
        return context.setupDataStore.data
            .map { preferences ->
                preferences[SetupPreferences.AGE] ?: 18
            }
            .first()
    }

    fun startStreaming(context: Context, csvFileName: String) {
        if (simulationJob?.isActive == true) {
            Log.i("SimulationManager", "Simulation already running")
            return
        }

        simulationJob = scope.launch {
            try {
                Log.i("SimulationManager", "Starting health data simulation")

                // Update simulation state
                _simulationState.value = _simulationState.value.copy(isRunning = true)

                // Load previous global state - this persists across app restarts
                loadGlobalSimulationState(context)

                Log.i("SimulationManager", "Reading CSV file: $csvFileName")
                val allLines = context.assets.open(csvFileName).bufferedReader().readLines()

                if (allLines.size <= 1) {
                    Log.e("SimulationManager", "CSV file has no data rows")
                    return@launch
                }

                Log.i("SimulationManager", "Successfully read ${allLines.size} lines from CSV")

                val header = allLines.first()
                dataLines = allLines.drop(1)

                // Update total records count
                _simulationState.value = _simulationState.value.copy(
                    totalRecords = dataLines.size,
                    currentIndex = currentIndex
                )

                val columnIndexMap = header.split(',')
                    .mapIndexed { index, name -> name.trim() to index }.toMap()

                val startTimeIndex = columnIndexMap["StartTime"] ?: run {
                    Log.e("SimulationManager", "StartTime column not found in CSV")
                    return@launch
                }

                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

                // Enhanced evaluation mode logic
                val (targetDate, historicalStartDate, historicalEndDate) = if (EvaluationState.isEvaluationMode.value) {
                    Triple(
                        EvaluationState.EVALUATION_DATE,
                        LocalDate.of(2023, 8, 1),
                        LocalDate.of(2023, 8, 18)
                    )
                } else {
                    Triple(LocalDate.now(), null, null)
                }

                // Always load historical data first in evaluation mode
                if (EvaluationState.isEvaluationMode.value && historicalStartDate != null && historicalEndDate != null) {
                    Log.i("SimulationManager", "Loading historical data for evaluation mode...")
                    loadHistoricalData(context, allLines, historicalStartDate, historicalEndDate)
                }

                // Start UI streaming job for continuous updates
                startUIStreamingJob()

                // Handle synchronization for interrupted simulation
                if (simulationStartTime > 0 && isPaused) {
                    Log.i("SimulationManager", "Resuming interrupted simulation - synchronizing missed data")
                    synchronizeInterruptedDataEnhanced(context, columnIndexMap, startTimeIndex, formatter, targetDate)
                    isPaused = false
                    _simulationState.value = _simulationState.value.copy(isPaused = false)
                } else if (simulationStartTime == 0L) {
                    // First time starting simulation
                    simulationStartTime = System.currentTimeMillis()
                    lastRecordTime = simulationStartTime

                    // Find matching records for current day
                    val bestMatchIdx = findBestMatchingRecord(dataLines, startTimeIndex, formatter, targetDate)
                    currentIndex = if (bestMatchIdx >= 0) bestMatchIdx else 0

                    // Initialize cumulative values from first record
                    initializeCumulativeValues(columnIndexMap)

                    saveGlobalSimulationState(context)
                }

                // Main simulation loop - runs until all data is processed
                runContinuousSimulationLoop(context, columnIndexMap, startTimeIndex, formatter, targetDate)

            } catch (e: IOException) {
                Log.e("SimulationManager", "Error reading CSV file: $csvFileName", e)
                _simulationState.value = _simulationState.value.copy(isRunning = false)
            } catch (e: Exception) {
                Log.e("SimulationManager", "Unexpected error in simulation", e)
                _simulationState.value = _simulationState.value.copy(isRunning = false)
            }
        }
    }

    private fun startUIStreamingJob() {
        uiStreamingJob?.cancel()
        uiStreamingJob = scope.launch {
            while (scope.isActive) {
                // Process pending UI updates
                synchronized(pendingUIUpdates) {
                    if (pendingUIUpdates.isNotEmpty()) {
                        val nextUpdate = pendingUIUpdates.removeAt(0)
                        _healthDataStream.value = nextUpdate
                        Log.v("SimulationManager", "Emitted pending UI update: HR=${nextUpdate.heartRate}")
                    }
                }
                delay(100) // Check for updates every 100ms
            }
        }
    }

    private suspend fun synchronizeInterruptedDataEnhanced(
        context: Context,
        columnIndexMap: Map<String, Int>,
        startTimeIndex: Int,
        formatter: DateTimeFormatter,
        targetDate: LocalDate
    ) {
        synchronizationJob = scope.launch {
            val currentTime = System.currentTimeMillis()
            val interruptedDuration = currentTime - lastPauseTime

            Log.i("SimulationManager", "Enhanced synchronization: ${interruptedDuration}ms from index $currentIndex")

            _synchronizationProgress.value = 0f

            // Calculate how many records should have been processed during interruption
            val estimatedRecordsPerMinute = 1.0
            val missedMinutes = interruptedDuration / (60 * 1000)
            val recordsToSync = (missedMinutes * estimatedRecordsPerMinute).toInt().coerceAtLeast(1)

            Log.i("SimulationManager", "Synchronizing $recordsToSync missed records with UI updates")

            val shortTermDb = HealthDatabase.getInstance(context)
            val shortTermDao = shortTermDb.healthRecordDao()
            val archiveDb = ArchiveDatabase.getInstance(context)
            val archiveDao = archiveDb.archiveRecordDao()

            // Emit all missed records with UI updates
            val syncStartTime = lastPauseTime
            val intervalMs = if (recordsToSync > 1) interruptedDuration / recordsToSync else interruptedDuration

            repeat(recordsToSync) { i ->
                if (currentIndex >= dataLines.size) {
                    Log.i("SimulationManager", "Reached end of data during sync")
                    return@repeat
                }

                val line = dataLines[currentIndex]
                val values = line.split(',')
                val timestamp = syncStartTime + (i * intervalMs)

                try {
                    val record = parseRecord(values, columnIndexMap, lastKnownRecord)

                    // Update cumulative values
                    cumulativeSteps = record.stepCount
                    cumulativeEnergy = record.activeEnergyBurned
                    lastKnownRecord = record

                    // Queue for UI update
                    synchronized(pendingUIUpdates) {
                        pendingUIUpdates.add(record)
                    }

                    // Store in databases
                    launch {
                        try {
                            shortTermDao.insert(
                                HealthRecordEntity(
                                    timestamp = timestamp,
                                    heartRate = record.heartRate,
                                    stepCount = record.stepCount,
                                    activeEnergyBurned = record.activeEnergyBurned,
                                    cyclePhase = record.cyclePhase,
                                    cycleDay = record.cycleDay,
                                    basalEnergyBurned = record.basalEnergyBurned,
                                    vo2Max = record.vo2Max,
                                    restingHeartRate = record.restingHeartRate,
                                    basalBodyTemperature = record.basalBodyTemperature
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("SimulationManager", "Failed to insert short-term record during sync", e)
                        }
                    }

                    launch {
                        try {
                            archiveDao.insert(
                                ArchiveRecordEntity(
                                    timestamp = timestamp,
                                    heartRate = record.heartRate,
                                    stepCount = record.stepCount,
                                    activeEnergyBurned = record.activeEnergyBurned,
                                    basalEnergyBurned = record.basalEnergyBurned,
                                    cyclePhase = record.cyclePhase,
                                    cycleDay = record.cycleDay,
                                    vo2Max = record.vo2Max,
                                    restingHeartRate = record.restingHeartRate,
                                    basalBodyTemperature = record.basalBodyTemperature
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("SimulationManager", "Failed to insert archive record during sync", e)
                        }
                    }

                    currentIndex++

                    // Update progress
                    val progress = (i + 1).toFloat() / recordsToSync
                    _synchronizationProgress.value = progress
                    _simulationState.value = _simulationState.value.copy(
                        currentIndex = currentIndex,
                        progressPercentage = (currentIndex.toFloat() / dataLines.size) * 100f
                    )

                    // Controlled delay to prevent system overload while maintaining UI responsiveness
                    delay(50) // Faster than original 10ms but still controlled

                } catch (e: Exception) {
                    Log.e("SimulationManager", "Error synchronizing record at index $currentIndex", e)
                    currentIndex++
                }
            }

            // Update timing
            lastRecordTime = currentTime
            totalPauseDuration += interruptedDuration
            lastPauseTime = 0L

            saveGlobalSimulationState(context)
            _synchronizationProgress.value = 1f

            Log.i("SimulationManager", "Enhanced synchronization complete. Updated to index $currentIndex")
        }

        synchronizationJob?.join() // Wait for synchronization to complete
    }

    private suspend fun runContinuousSimulationLoop(
        context: Context,
        columnIndexMap: Map<String, Int>,
        startTimeIndex: Int,
        formatter: DateTimeFormatter,
        targetDate: LocalDate
    ) {
        val shortTermDb = HealthDatabase.getInstance(context)
        val shortTermDao = shortTermDb.healthRecordDao()
        val archiveDb = ArchiveDatabase.getInstance(context)
        val archiveDao = archiveDb.archiveRecordDao()

        var previousRecordTime: LocalDateTime? = null

        // Initialize simulation day
        currentSimulationDay = if (EvaluationState.isEvaluationMode.value) {
            targetDate.dayOfMonth
        } else {
            LocalDateTime.now().dayOfMonth
        }

        Log.i("SimulationManager", "Starting simulation loop from index $currentIndex of ${dataLines.size} records")

        while (scope.isActive && currentIndex < dataLines.size && !isSimulationComplete) {
            // Handle pause state
            if (isPaused) {
                _simulationState.value = _simulationState.value.copy(isPaused = true)
                delay(1000)
                continue
            }

            val line = dataLines[currentIndex]
            val values = line.split(',')

            val currentRecordTime = try {
                val csvTime = LocalDateTime.parse(values[startTimeIndex], formatter)
                if (EvaluationState.isEvaluationMode.value) {
                    LocalDateTime.of(LocalDateTime.now().toLocalDate(), csvTime.toLocalTime())
                } else {
                    csvTime.withYear(LocalDateTime.now().year)
                        .withMonth(LocalDateTime.now().monthValue)
                        .withDayOfMonth(LocalDateTime.now().dayOfMonth)
                }
            } catch (e: Exception) {
                Log.e("SimulationManager", "Error parsing time at index $currentIndex", e)
                currentIndex++
                continue
            }

            // Handle day rollover
            val recordDay = if (EvaluationState.isEvaluationMode.value) {
                targetDate.dayOfMonth
            } else {
                currentRecordTime.dayOfMonth
            }

            if (recordDay != currentSimulationDay) {
                currentSimulationDay = recordDay
                Log.i("SimulationManager", "New day detected. Day: $currentSimulationDay")
            }

            // Calculate timing delay
            val delayMillis = if (previousRecordTime == null) {
                1000L // First record delay
            } else {
                val calculatedDelay = ChronoUnit.MILLIS.between(previousRecordTime, currentRecordTime)
                calculatedDelay.coerceIn(100L, 60000L) // Between 100ms and 1 minute
            }

            delay(delayMillis)

            if (scope.isActive && !isPaused) {
                try {
                    val newRecord = parseRecord(values, columnIndexMap, lastKnownRecord)

                    // Update cumulative totals
                    cumulativeSteps = newRecord.stepCount
                    cumulativeEnergy = newRecord.activeEnergyBurned

                    // Emit to stream for UI updates (immediate update)
                    _healthDataStream.value = newRecord
                    lastKnownRecord = newRecord
                    previousRecordTime = currentRecordTime

                    val timestamp = System.currentTimeMillis()
                    lastRecordTime = timestamp

                    // Update simulation state
                    _simulationState.value = _simulationState.value.copy(
                        currentIndex = currentIndex,
                        progressPercentage = (currentIndex.toFloat() / dataLines.size) * 100f,
                        lastUpdateTime = timestamp
                    )

                    // Store in databases
                    scope.launch {
                        try {
                            shortTermDao.insert(
                                HealthRecordEntity(
                                    timestamp = timestamp,
                                    heartRate = newRecord.heartRate,
                                    stepCount = newRecord.stepCount,
                                    activeEnergyBurned = newRecord.activeEnergyBurned,
                                    cyclePhase = newRecord.cyclePhase,
                                    cycleDay = newRecord.cycleDay,
                                    basalEnergyBurned = newRecord.basalEnergyBurned,
                                    vo2Max = newRecord.vo2Max,
                                    restingHeartRate = newRecord.restingHeartRate,
                                    basalBodyTemperature = newRecord.basalBodyTemperature
                                )
                            )

                            // Clean old short-term data
                            val cutoff = timestamp - TimeUnit.HOURS.toMillis(3)
                            shortTermDao.deleteOlderThan(cutoff)
                        } catch (e: Exception) {
                            Log.e("SimulationManager", "Failed to insert short-term record", e)
                        }
                    }

                    scope.launch {
                        try {
                            archiveDao.insert(
                                ArchiveRecordEntity(
                                    timestamp = timestamp,
                                    heartRate = newRecord.heartRate,
                                    stepCount = newRecord.stepCount,
                                    activeEnergyBurned = newRecord.activeEnergyBurned,
                                    basalEnergyBurned = newRecord.basalEnergyBurned,
                                    cyclePhase = newRecord.cyclePhase,
                                    cycleDay = newRecord.cycleDay,
                                    vo2Max = newRecord.vo2Max,
                                    restingHeartRate = newRecord.restingHeartRate,
                                    basalBodyTemperature = newRecord.basalBodyTemperature
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("SimulationManager", "Failed to insert archive record", e)
                        }
                    }

                    // Persist state periodically
                    if (currentIndex % 10 == 0) {
                        saveGlobalSimulationState(context)
                    }

                } catch (e: Exception) {
                    Log.e("SimulationManager", "Error processing record at index $currentIndex", e)
                }
            }

            currentIndex++
        }

        // Simulation complete
        if (currentIndex >= dataLines.size) {
            isSimulationComplete = true
            _simulationState.value = _simulationState.value.copy(
                isRunning = false,
                progressPercentage = 100f
            )
            Log.i("SimulationManager", "Simulation completed - all ${dataLines.size} records processed")

            // Final state save
            saveGlobalSimulationState(context)

            // Keep the stream alive for visualizations but stop processing
            while (scope.isActive) {
                delay(5000) // Keep alive but idle
            }
        }
    }

    // Enhanced pause/resume methods with proper state management
    fun pauseSimulation(context: Context) {
        if (!isPaused && !isSimulationComplete) {
            isPaused = true
            lastPauseTime = System.currentTimeMillis()
            _simulationState.value = _simulationState.value.copy(isPaused = true)

            scope.launch {
                saveGlobalSimulationState(context)
            }
            Log.i("SimulationManager", "Simulation paused at $lastPauseTime")
        }
    }

    fun resumeSimulation(context: Context) {
        if (isPaused && !isSimulationComplete) {
            Log.i("SimulationManager", "Resuming simulation from pause state")
            isPaused = false
            _simulationState.value = _simulationState.value.copy(isPaused = false)

            scope.launch {
                saveGlobalSimulationState(context)
            }
        }
    }

    fun stopStreaming() {
        simulationJob?.cancel()
        synchronizationJob?.cancel()
        uiStreamingJob?.cancel()
        simulationJob = null
        synchronizationJob = null
        uiStreamingJob = null
        isPaused = false

        // Clear pending updates
        synchronized(pendingUIUpdates) {
            pendingUIUpdates.clear()
        }

        _simulationState.value = SimulationState()
        _synchronizationProgress.value = 0f

        Log.i("SimulationManager", "Simulation stopped")
    }

    fun resetSimulation(context: Context) {
        stopStreaming()

        scope.launch {
            context.simulationDataStore.edit { prefs ->
                prefs.clear()
            }

            simulationStartTime = 0L
            lastPauseTime = 0L
            totalPauseDuration = 0L
            currentIndex = 0
            isPaused = false
            lastRecordTime = 0L
            cumulativeSteps = 0
            cumulativeEnergy = 0.0
            isSimulationComplete = false

            _simulationState.value = SimulationState()
            _healthDataStream.value = HealthDataRecord()
            _synchronizationProgress.value = 0f

            Log.i("SimulationManager", "Simulation reset to initial state")
        }
    }

    // Public getters for enhanced state monitoring
    fun getCumulativeSteps(): Int = cumulativeSteps
    fun getCumulativeEnergy(): Double = cumulativeEnergy
    fun isComplete(): Boolean = isSimulationComplete
    fun getCurrentProgress(): Pair<Int, Int> = Pair(currentIndex, dataLines.size)
    fun isPaused(): Boolean = isPaused
    fun isRunning(): Boolean = simulationJob?.isActive == true

    // Remaining helper methods stay the same...
    private suspend fun loadGlobalSimulationState(context: Context) {
        context.simulationDataStore.data.first().let { prefs ->
            simulationStartTime = prefs[KEY_SIMULATION_START_TIME] ?: 0L
            lastPauseTime = prefs[KEY_LAST_PAUSE_TIME] ?: 0L
            totalPauseDuration = prefs[KEY_TOTAL_PAUSE_DURATION] ?: 0L
            currentIndex = prefs[KEY_INDEX] ?: 0
            isPaused = prefs[KEY_IS_PAUSED] ?: false
            lastRecordTime = prefs[KEY_LAST_RECORD_TIME] ?: 0L
            cumulativeSteps = prefs[KEY_CUMULATIVE_STEPS] ?: 0
            cumulativeEnergy = (prefs[KEY_CUMULATIVE_ENERGY] ?: 0L) / 1000.0

            Log.i("SimulationManager",
                "Loaded global state - Start: $simulationStartTime, Index: $currentIndex, " +
                        "Paused: $isPaused, LastRecord: $lastRecordTime, Steps: $cumulativeSteps"
            )
        }
    }

    private suspend fun saveGlobalSimulationState(context: Context) {
        context.simulationDataStore.edit { prefs ->
            prefs[KEY_SIMULATION_START_TIME] = simulationStartTime
            prefs[KEY_LAST_PAUSE_TIME] = lastPauseTime
            prefs[KEY_TOTAL_PAUSE_DURATION] = totalPauseDuration
            prefs[KEY_INDEX] = currentIndex
            prefs[KEY_IS_PAUSED] = isPaused
            prefs[KEY_LAST_RECORD_TIME] = lastRecordTime
            prefs[KEY_CUMULATIVE_STEPS] = cumulativeSteps
            prefs[KEY_CUMULATIVE_ENERGY] = (cumulativeEnergy * 1000).toLong()
        }
    }

    private fun initializeCumulativeValues(columnIndexMap: Map<String, Int>) {
        if (currentIndex < dataLines.size) {
            try {
                val initialLine = dataLines[currentIndex]
                val initialValues = initialLine.split(',')
                val initialRecord = parseRecord(initialValues, columnIndexMap, HealthDataRecord())

                cumulativeSteps = initialRecord.stepCount
                cumulativeEnergy = initialRecord.activeEnergyBurned
                lastKnownRecord = initialRecord

                Log.i("SimulationManager", "Initialized with steps: $cumulativeSteps, energy: $cumulativeEnergy")
            } catch (e: Exception) {
                Log.e("SimulationManager", "Failed to initialize cumulative values", e)
            }
        }
    }

    private fun findBestMatchingRecord(
        dataLines: List<String>,
        startTimeIndex: Int,
        formatter: DateTimeFormatter,
        targetDate: LocalDate
    ): Int {
        val systemNow = LocalDateTime.now()
        var bestMatchIdx = -1
        var closestTimeDiff = Long.MAX_VALUE

        dataLines.forEachIndexed { idx, line ->
            val parts = line.split(',')
            try {
                val originalRecordTime = LocalDateTime.parse(parts[startTimeIndex], formatter)

                if (originalRecordTime.toLocalDate().isEqual(targetDate)) {
                    val timeDiff = kotlin.math.abs(
                        ChronoUnit.MINUTES.between(originalRecordTime.toLocalTime(), systemNow.toLocalTime())
                    )

                    if (timeDiff < closestTimeDiff) {
                        closestTimeDiff = timeDiff
                        bestMatchIdx = idx
                    }
                }
            } catch (e: Exception) {
                // Skip invalid records
            }
        }

        return bestMatchIdx
    }

    // Key fixes needed in SimulationManager.kt for proper evaluation mode step analytics

    // 1. Fix in loadHistoricalData method - ensure proper timestamp conversion
    private suspend fun loadHistoricalData(
        context: Context,
        allLines: List<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ) {
        Log.i("SimulationManager", "Loading historical data from $startDate to $endDate for step analytics")

        try {
            val header = allLines.first()
            val dataLines = allLines.drop(1)
            val columnIndexMap = header.split(',')
                .mapIndexed { index, name -> name.trim() to index }.toMap()
            val startTimeIndex = columnIndexMap["StartTime"] ?: return
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

            val archiveDb = ArchiveDatabase.getInstance(context)
            val archiveDao = archiveDb.archiveRecordDao()

            // Process in smaller batches to avoid memory issues
            val batchSize = 1000
            var insertedCount = 0

            // CRITICAL FIX: Use the actual date range from evaluation state
            val evaluationStartDate = EvaluationState.HISTORICAL_START_DATE  // 2023-08-01
            val evaluationEndDate = EvaluationState.HISTORICAL_END_DATE      // 2023-08-18

            // Pre-filter data for the correct date range
            val relevantLines = dataLines.mapNotNull { line ->
                try {
                    val values = line.split(',')
                    val recordTime = LocalDateTime.parse(values[startTimeIndex], formatter)
                    val recordDate = recordTime.toLocalDate()

                    // Check if the record falls within our evaluation historical range
                    if (!recordDate.isBefore(evaluationStartDate) && !recordDate.isAfter(evaluationEndDate)) {
                        Log.d("SimulationManager", "Including historical record for date: $recordDate")
                        Pair(recordTime, values)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    Log.v("SimulationManager", "Skipping invalid record: ${e.message}")
                    null
                }
            }

            Log.i("SimulationManager", "Found ${relevantLines.size} relevant historical records to insert")

            // Insert in batches with proper timestamp conversion
            relevantLines.chunked(batchSize).forEach { batch ->
                batch.forEach { (originalTime, values) ->
                    try {
                        val record = parseRecord(values, columnIndexMap, HealthDataRecord())

                        // CRITICAL: Use the original timestamp from the 2023 data
                        val historicalTimestamp = originalTime.atZone(ZoneId.systemDefault())
                            .toInstant().toEpochMilli()

                        archiveDao.insert(
                            ArchiveRecordEntity(
                                timestamp = historicalTimestamp,
                                heartRate = record.heartRate,
                                stepCount = record.stepCount,
                                activeEnergyBurned = record.activeEnergyBurned,
                                basalEnergyBurned = record.basalEnergyBurned,
                                cyclePhase = record.cyclePhase,
                                cycleDay = record.cycleDay,
                                vo2Max = record.vo2Max,
                                restingHeartRate = record.restingHeartRate,
                                basalBodyTemperature = record.basalBodyTemperature
                            )
                        )
                        insertedCount++

                        // Log sample records for verification
                        if (insertedCount <= 10) {
                            Log.d("SimulationManager", "Inserted historical record #$insertedCount: " +
                                    "Date=$originalTime, Steps=${record.stepCount}, " +
                                    "Phase=${record.cyclePhase}, Day=${record.cycleDay}")
                        }
                    } catch (e: Exception) {
                        Log.w("SimulationManager", "Error inserting historical record: ${e.message}")
                    }
                }
                // Small delay between batches to prevent overwhelming the system
                delay(50)
            }

            Log.i("SimulationManager", "Successfully inserted $insertedCount historical records for step analytics")

        } catch (e: Exception) {
            Log.e("SimulationManager", "Error loading historical data for step analytics", e)
        }
    }

    // 2. Enhanced method to get daily step aggregates for step analytics
    suspend fun getDailyStepDataForRange(context: Context, startDate: LocalDate, endDate: LocalDate): List<Pair<LocalDate, Int>> {
        return withContext(Dispatchers.IO) {
            try {
                val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()
                val dailySteps = mutableListOf<Pair<LocalDate, Int>>()

                var currentDate = startDate
                while (!currentDate.isAfter(endDate)) {
                    val dayStart = currentDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val dayEnd = currentDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

                    val dayRecords = archiveDao.getRecordsBetween(dayStart, dayEnd)

                    val dailyStepCount = if (dayRecords.isNotEmpty()) {
                        // Get the maximum step count for the day (assuming cumulative steps)
                        val sortedRecords = dayRecords.sortedBy { it.timestamp }
                        val maxSteps = sortedRecords.maxOfOrNull { it.stepCount } ?: 0

                        // Alternative: Calculate daily increment
                        val firstRecord = sortedRecords.first()
                        val lastRecord = sortedRecords.last()
                        val increment = lastRecord.stepCount - firstRecord.stepCount

                        // Use whichever method makes sense for your data structure
                        maxOf(maxSteps, increment, lastRecord.stepCount)
                    } else {
                        0
                    }

                    dailySteps.add(Pair(currentDate, dailyStepCount))
                    Log.d("SimulationManager", "Daily steps for $currentDate: $dailyStepCount")

                    currentDate = currentDate.plusDays(1)
                }

                dailySteps
            } catch (e: Exception) {
                Log.e("SimulationManager", "Error getting daily step data", e)
                emptyList()
            }
        }
    }

    // 3. Enhanced method to verify evaluation mode data integrity
    suspend fun verifyEvaluationModeData(context: Context): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val archiveDao = ArchiveDatabase.getInstance(context).archiveRecordDao()

                // Check if we have data for the historical range
                val historicalStartTimestamp = EvaluationState.HISTORICAL_START_DATE
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val historicalEndTimestamp = EvaluationState.HISTORICAL_END_DATE
                    .plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

                val historicalRecords = archiveDao.getRecordsBetween(historicalStartTimestamp, historicalEndTimestamp)

                Log.i("SimulationManager", "Evaluation mode data verification:")
                Log.i("SimulationManager", "Historical records found: ${historicalRecords.size}")
                Log.i("SimulationManager", "Date range: ${EvaluationState.HISTORICAL_START_DATE} to ${EvaluationState.HISTORICAL_END_DATE}")

                // Verify we have data for each day
                val daysWithData = historicalRecords
                    .groupBy { record ->
                        val instant = java.time.Instant.ofEpochMilli(record.timestamp)
                        instant.atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    .keys
                    .sorted()

                Log.i("SimulationManager", "Days with historical data: $daysWithData")

                val expectedDays = ChronoUnit.DAYS.between(
                    EvaluationState.HISTORICAL_START_DATE,
                    EvaluationState.HISTORICAL_END_DATE.plusDays(1)
                ).toInt()

                val hasCompleteData = daysWithData.size >= (expectedDays * 0.8) // At least 80% coverage

                Log.i("SimulationManager", "Expected days: $expectedDays, Got days: ${daysWithData.size}, Complete: $hasCompleteData")

                hasCompleteData

            } catch (e: Exception) {
                Log.e("SimulationManager", "Error verifying evaluation mode data", e)
                false
            }
        }
    }

    private fun parseRecord(
        values: List<String>,
        columnIndexMap: Map<String, Int>,
        lastRecord: HealthDataRecord
    ): HealthDataRecord {
        return try {
            val hrIndex = columnIndexMap["HeartRate"]
            val stepsIndex = columnIndexMap["StepCount"]
            val activeEnergyIndex = columnIndexMap["ActiveEnergyBurned"]
            val basalEnergyIndex = columnIndexMap["BasalEnergyBurned"]
            val cyclePhaseIndex = columnIndexMap["MenstrualCyclePhase"]
            val cycleDayIndex = columnIndexMap["MenstrualCycle Day"]
            val vo2MaxIndex = columnIndexMap["VO2Max"]
            val restingHrIndex = columnIndexMap["RestingHeartRate"]
            val bbtIndex = columnIndexMap["BasalBodyTemperature"]

            HealthDataRecord(
                heartRate = hrIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.heartRate,

                stepCount = stepsIndex?.let {
                    values.getOrNull(it)?.trim()?.toIntOrNull()
                } ?: lastRecord.stepCount,

                activeEnergyBurned = activeEnergyIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.activeEnergyBurned,

                basalEnergyBurned = basalEnergyIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.basalEnergyBurned,

                cyclePhase = cyclePhaseIndex?.let {
                    values.getOrNull(it)?.trim()?.takeIf { phase -> phase.isNotBlank() }
                } ?: lastRecord.cyclePhase,

                cycleDay = cycleDayIndex?.let {
                    values.getOrNull(it)?.trim()?.toIntOrNull()
                } ?: lastRecord.cycleDay,

                vo2Max = vo2MaxIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.vo2Max,

                restingHeartRate = restingHrIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.restingHeartRate,

                basalBodyTemperature = bbtIndex?.let {
                    values.getOrNull(it)?.trim()?.toDoubleOrNull()
                } ?: lastRecord.basalBodyTemperature
            )
        } catch (e: Exception) {
            Log.e("SimulationManager", "Error parsing record: ${e.message}", e)
            lastRecord
        }
    }
}