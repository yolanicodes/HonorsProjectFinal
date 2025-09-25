// Enhanced SimulationManager.kt
package com.example.honoursproject.dataservices

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
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

    private val _healthDataStream = MutableStateFlow(HealthDataRecord())
    val healthDataStream = _healthDataStream.asStateFlow()

    // Global simulation state tracking
    private var simulationJob: Job? = null
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

                // Load previous global state - this persists across app restarts
                loadGlobalSimulationState(context)

                Log.i("SimulationManager", "Reading CSV file: $csvFileName")
                val allLines = context.assets.open(csvFileName).bufferedReader().readLines()

                if (allLines.size <= 1) {
                    Log.e("SimulationManager", "CSV file has no data rows")
                    return@launch
                }

                Log.i("SimulationManager", "Successfully read ${allLines.size} lines from CSV")

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

                val header = allLines.first()
                dataLines = allLines.drop(1)
                val columnIndexMap = header.split(',')
                    .mapIndexed { index, name -> name.trim() to index }.toMap()

                val startTimeIndex = columnIndexMap["StartTime"] ?: run {
                    Log.e("SimulationManager", "StartTime column not found in CSV")
                    return@launch
                }

                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

                // Handle synchronization for interrupted simulation
                if (simulationStartTime > 0 && isPaused) {
                    Log.i("SimulationManager", "Resuming interrupted simulation - synchronizing missed data")
                    synchronizeInterruptedData(context, columnIndexMap, startTimeIndex, formatter, targetDate)
                    isPaused = false
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
            } catch (e: Exception) {
                Log.e("SimulationManager", "Unexpected error in simulation", e)
            }
        }
    }

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

    private suspend fun synchronizeInterruptedData(
        context: Context,
        columnIndexMap: Map<String, Int>,
        startTimeIndex: Int,
        formatter: DateTimeFormatter,
        targetDate: LocalDate
    ) {
        val currentTime = System.currentTimeMillis()
        val interruptedDuration = currentTime - lastPauseTime

        Log.i("SimulationManager", "Synchronizing ${interruptedDuration}ms of interrupted data from index $currentIndex")

        // Calculate how many records should have been processed during interruption
        val estimatedRecordsPerMinute = 1.0 // Assume roughly 1 record per minute based on typical health data
        val missedMinutes = interruptedDuration / (60 * 1000)
        val recordsToSync = (missedMinutes * estimatedRecordsPerMinute).toInt().coerceAtLeast(1)

        Log.i("SimulationManager", "Synchronizing $recordsToSync missed records")

        val shortTermDb = HealthDatabase.getInstance(context)
        val shortTermDao = shortTermDb.healthRecordDao()
        val archiveDb = ArchiveDatabase.getInstance(context)
        val archiveDao = archiveDb.archiveRecordDao()

        // Emit all missed records quickly for database storage
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

                // Store with historical timestamp
                scope.launch {
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
                }

                scope.launch {
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
                }

                currentIndex++

                // Small delay to prevent overwhelming the system
                delay(10)

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

        Log.i("SimulationManager", "Synchronization complete. Updated to index $currentIndex")
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
                // Reset daily cumulative values but keep running totals
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

                    // Emit to stream for UI updates
                    _healthDataStream.value = newRecord
                    lastKnownRecord = newRecord
                    previousRecordTime = currentRecordTime

                    val timestamp = System.currentTimeMillis()
                    lastRecordTime = timestamp

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
            Log.i("SimulationManager", "Simulation completed - all ${dataLines.size} records processed")

            // Final state save
            saveGlobalSimulationState(context)

            // Keep the stream alive for visualizations but stop processing
            while (scope.isActive) {
                delay(5000) // Keep alive but idle
            }
        }
    }

    fun pauseSimulation(context: Context) {
        if (!isPaused && !isSimulationComplete) {
            isPaused = true
            lastPauseTime = System.currentTimeMillis()
            scope.launch {
                saveGlobalSimulationState(context)
            }
            Log.i("SimulationManager", "Simulation paused at $lastPauseTime")
        }
    }

    fun resumeSimulation(context: Context) {
        if (isPaused && !isSimulationComplete) {
            Log.i("SimulationManager", "Resuming simulation from pause state")
            // The synchronization will happen automatically when startStreaming is called
            isPaused = false
            scope.launch {
                saveGlobalSimulationState(context)
            }
        }
    }

    fun stopStreaming() {
        simulationJob?.cancel()
        simulationJob = null
        isPaused = false
        Log.i("SimulationManager", "Simulation stopped")
    }

    fun resetSimulation(context: Context) {
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

            Log.i("SimulationManager", "Simulation reset to initial state")
        }
    }

    fun getCumulativeSteps(): Int = cumulativeSteps
    fun getCumulativeEnergy(): Double = cumulativeEnergy
    fun isComplete(): Boolean = isSimulationComplete
    fun getCurrentProgress(): Pair<Int, Int> = Pair(currentIndex, dataLines.size)

    // Helper methods remain mostly the same
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

    private suspend fun loadHistoricalData(
        context: Context,
        allLines: List<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ) {
        Log.i("SimulationManager", "Loading historical data from $startDate to $endDate")

        val header = allLines.first()
        val dataLines = allLines.drop(1)
        val columnIndexMap = header.split(',')
            .mapIndexed { index, name -> name.trim() to index }.toMap()
        val startTimeIndex = columnIndexMap["StartTime"] ?: return
        val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

        val archiveDb = ArchiveDatabase.getInstance(context)
        val archiveDao = archiveDb.archiveRecordDao()

        val historicalRecords = dataLines.mapNotNull { line ->
            try {
                val values = line.split(',')
                val recordTime = LocalDateTime.parse(values[startTimeIndex], formatter)
                val recordDate = recordTime.toLocalDate()

                if (!recordDate.isBefore(startDate) && !recordDate.isAfter(endDate)) {
                    Pair(recordTime, values)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        val sortedRecords = historicalRecords.sortedBy { it.first }
        var insertedCount = 0

        sortedRecords.forEach { (originalTime, values) ->
            try {
                val record = parseRecord(values, columnIndexMap, HealthDataRecord())
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
            } catch (e: Exception) {
                Log.e("SimulationManager", "Error inserting historical record", e)
            }
        }

        Log.i("SimulationManager", "Successfully inserted $insertedCount historical records")
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