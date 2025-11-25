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
import java.time.LocalDate
import java.time.ZoneId

val Context.simulationDataStore by preferencesDataStore(name = "simulation_prefs")

data class SimulationState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val currentIndex: Int = 0,
    val totalRecords: Int = 0,
    val lastUpdateTime: Long = 0L
)

/**
 * FIXED SimulationManager with proper evaluation mode handling
 */
object SimulationManager {
    private const val HEARTBEAT_INTERVAL_MS = 2000L
    private const val HISTORICAL_EMISSION_INTERVAL = 50
    private const val VERIFICATION_DELAY_MS = 1000L
    private const val MAX_VERIFICATION_ATTEMPTS = 10
    private var heartbeatJob: Job? = null
    private const val TAG = "SimulationManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var simulationJob: Job? = null

    // State flows
    private val _simulationState = MutableStateFlow(SimulationState())
    val simulationState = _simulationState.asStateFlow()

    private val _historicalLoadingProgress = MutableStateFlow(0f)
    val historicalLoadingProgress = _historicalLoadingProgress.asStateFlow()

    private val _isHistoricalLoadingComplete = MutableStateFlow(false)
    val isHistoricalLoadingComplete = _isHistoricalLoadingComplete.asStateFlow()

    // NEW: Track if data is accessible to UIs
    private val _isDataAccessible = MutableStateFlow(false)
    val isDataAccessible = _isDataAccessible.asStateFlow()

    // Dependencies
    private lateinit var repository: HealthDataRepository

    // Tracking variables
    private var lastKnownCyclePhase = ""
    private var lastKnownCycleDay = 0
    private var cumulativeSteps = 0
    private var cumulativeEnergy = 0.0

    /**
     * Initialize with repository (Dependency Injection)
     */
    fun initialize(repo: HealthDataRepository) {
        repository = repo
        Log.i(TAG, "Initialized with repository")
    }

    /**
     * Start streaming pipeline with smart loading
     *
     * Flow:
     * 1. Check if historical data already loaded
     * 2. If yes: Load from database (fast)
     * 3. If no: Load from CSV (slow, one-time)
     * 4. Verify data accessibility
     * 5. Start heartbeat and live simulation
     */
    suspend fun startStreaming(context: Context, csvFileName: String) {
        if (simulationJob?.isActive == true) {
            Log.w(TAG, "Simulation already running")
            return
        }

        Log.i(TAG, "===== Starting Simulation Pipeline =====")

        // Reset accessibility flag
        _isDataAccessible.value = false

        // Check if we need to reload data
        val shouldReload = HistoricalDataManager.shouldReloadData(context)

        if (!shouldReload) {
            Log.i(TAG, "✓ Historical data already exists, loading from database...")

            // Show existing data info
            val info = HistoricalDataManager.getHistoricalDataInfo(context)
            info?.let {
                Log.i(TAG, "  Loaded on: ${java.util.Date(it.loadTimestamp)}")
                Log.i(TAG, "  Record count: ${it.recordCount}")
                Log.i(TAG, "  Cutoff time: ${it.cutoffTime}")
                Log.i(TAG, "  Was evaluation mode: ${it.wasEvaluationMode}")
            }

            // Get database summary
            val dbSummary = HistoricalDataManager.getDatabaseSummary(context)
            Log.i(TAG, "  Archive records: ${dbSummary.archiveRecordCount}")
            Log.i(TAG, "  Date range: ${java.util.Date(dbSummary.earliestTimestamp)} to ${java.util.Date(dbSummary.latestTimestamp)}")

            // Load latest snapshot from database
            loadLatestSnapshotFromDatabase(context)

            _historicalLoadingProgress.value = 1f
            _isHistoricalLoadingComplete.value = true
        } else {
            Log.i(TAG, "Loading historical data from CSV for first time...")

            // Fresh load from CSV
            loadHistoricalDataBlocking(context, csvFileName)

            // Wait for completion
            _isHistoricalLoadingComplete.first { it }
        }

        // CRITICAL: Verify data is accessible before proceeding
        waitForDataAccessibility(context)

        Log.i(TAG, "✓ Historical data accessible and ready for UIs")

        // Start heartbeat to keep data fresh
        startHeartbeat()

        // Start workout monitoring
        try {
            WorkoutSimulationManager.startMonitoring(context, "P102-Workout_Data.csv")
            Log.i(TAG, "✓ Workout countdown triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start workout monitoring", e)
        }

        // Start live simulation
        Log.i(TAG, "✓ Starting live simulation...")
        simulationJob = scope.launch {
            runLiveSimulation(context, csvFileName)
        }
    }

    /**
     * Load latest snapshot from database (for subsequent launches)
     * This is much faster than loading from CSV
     */
    private suspend fun loadLatestSnapshotFromDatabase(context: Context) {
        try {
            Log.i(TAG, "Loading snapshot from database...")

            val cutoffTime = if (EvaluationState.isEvaluationMode.value) {
                val evalDate = EvaluationState.EVALUATION_DATE
                val currentTime = LocalDateTime.now()
                LocalDateTime.of(evalDate, currentTime.toLocalTime())
            } else {
                LocalDateTime.now()
            }

            val cutoffTimestamp = cutoffTime.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            Log.i(TAG, "  Cutoff time: $cutoffTime")
            Log.i(TAG, "  Cutoff timestamp: $cutoffTimestamp")

            // Get records around cutoff time (within 5 minutes before)
            val dao = ArchiveDatabase.getInstance(context).archiveRecordDao()
            val records = dao.getRecordsBetween(
                cutoffTimestamp - (5 * 60 * 1000), // 5 minutes before
                cutoffTimestamp
            )

            Log.i(TAG, "  Found ${records.size} records near cutoff")

            if (records.isEmpty()) {
                Log.w(TAG, "⚠ No records found near cutoff time, trying broader range...")

                // Try getting any records up to cutoff
                val allRecords = dao.getRecordsBetween(0, cutoffTimestamp)

                if (allRecords.isEmpty()) {
                    Log.e(TAG, "❌ No historical records found in database!")
                    return
                }

                val latestRecord = allRecords.maxByOrNull { it.timestamp }!!
                Log.i(TAG, "  Using latest available record from ${java.util.Date(latestRecord.timestamp)}")

                updateSnapshotFromRecord(latestRecord)
                return
            }

            // Get latest record from the set
            val latestRecord = records.maxByOrNull { it.timestamp }!!
            Log.i(TAG, "  Latest record timestamp: ${java.util.Date(latestRecord.timestamp)}")

            updateSnapshotFromRecord(latestRecord)

        } catch (e: Exception) {
            Log.e(TAG, "Error loading snapshot from database", e)
            e.printStackTrace()
        }
    }

    /**
     * Helper to update snapshot from a database record
     */
    private suspend fun updateSnapshotFromRecord(record: ArchiveRecordEntity) {
        // Update tracking variables
        cumulativeSteps = record.stepCount
        cumulativeEnergy = record.activeEnergyBurned
        lastKnownCyclePhase = record.cyclePhase
        lastKnownCycleDay = record.cycleDay

        // Emit snapshot
        val snapshot = HealthDataSnapshot(
            timestamp = System.currentTimeMillis(),
            heartRate = record.heartRate,
            vo2Max = record.vo2Max,
            restingHeartRate = record.restingHeartRate,
            basalBodyTemperature = record.basalBodyTemperature,
            basalEnergyBurned = record.basalEnergyBurned,
            cyclePhase = lastKnownCyclePhase,
            cycleDay = lastKnownCycleDay,
            cumulativeSteps = cumulativeSteps,
            cumulativeEnergyBurned = cumulativeEnergy,
            isHistoricalDataLoaded = true,
            dataSource = DataSource.DATABASE
        )

        HealthDataStreamManager.emitData(snapshot)

        Log.i(TAG, "✓ Loaded snapshot from database:")
        Log.i(TAG, "  Steps: $cumulativeSteps")
        Log.i(TAG, "  Energy: ${cumulativeEnergy.toInt()} kcal")
        Log.i(TAG, "  HR: ${record.heartRate.toInt()} bpm")
        Log.i(TAG, "  Cycle: $lastKnownCyclePhase Day $lastKnownCycleDay")
    }

    /**
     * Wait for data to be truly accessible to UIs
     * This prevents activities from launching before data is ready
     */
    private suspend fun waitForDataAccessibility(context: Context) {
        Log.i(TAG, "Verifying data accessibility for UIs...")

        var attempts = 0

        while (attempts < MAX_VERIFICATION_ATTEMPTS) {
            delay(VERIFICATION_DELAY_MS)

            // Check 1: HealthDataStreamManager has valid data
            val snapshot = HealthDataStreamManager.getCurrentSnapshot()

            val hasValidSnapshot = snapshot.isHistoricalDataLoaded &&
                    (snapshot.cumulativeSteps > 0 || snapshot.heartRate > 0)

            Log.d(TAG, "  Check 1 - Snapshot valid: $hasValidSnapshot " +
                    "(Steps=${snapshot.cumulativeSteps}, HR=${snapshot.heartRate.toInt()})")

            // Check 2: Repository can fetch data
            val hasDbData = try {
                val latestRecord = repository.getLatestRecord()
                latestRecord != null && latestRecord.timestamp > 0
            } catch (e: Exception) {
                Log.e(TAG, "  Check 2 - Repository error: ${e.message}")
                false
            }

            Log.d(TAG, "  Check 2 - Repository accessible: $hasDbData")

            // Check 3: Archive database has records
            val hasArchiveData = try {
                val dao = ArchiveDatabase.getInstance(context).archiveRecordDao()
                val count = dao.getAll().size
                Log.d(TAG, "  Check 3 - Archive has $count records")
                count > 0
            } catch (e: Exception) {
                Log.e(TAG, "  Check 3 - Archive error: ${e.message}")
                false
            }

            // All checks must pass
            if (hasValidSnapshot && hasDbData && hasArchiveData) {
                _isDataAccessible.value = true
                Log.i(TAG, "✓ Data accessibility verified after ${attempts + 1} attempts")
                Log.i(TAG, "  Final state: Steps=${snapshot.cumulativeSteps}, " +
                        "HR=${snapshot.heartRate.toInt()}, " +
                        "Cycle=${snapshot.cyclePhase} D${snapshot.cycleDay}")
                return
            }

            attempts++
            Log.d(TAG, "  Waiting for data accessibility... attempt $attempts/$MAX_VERIFICATION_ATTEMPTS")
        }

        Log.w(TAG, "⚠ Data accessibility verification timed out - proceeding anyway")
        _isDataAccessible.value = true // Proceed to prevent deadlock
    }

    /**
     * Load historical data from CSV (first-time load only)
     */
    private suspend fun loadHistoricalDataBlocking(context: Context, csvFileName: String) {
        try {
            Log.i(TAG, "Loading historical data from $csvFileName...")

            val allLines = context.assets.open(csvFileName).bufferedReader().readLines()
            val header = allLines.first()
            val dataLines = allLines.drop(1)

            val columnIndexMap = header.split(',')
                .mapIndexed { index, name -> name.trim() to index }
                .toMap()

            Log.i(TAG, "CSV Columns detected: ${columnIndexMap.keys}")

            val startTimeIndex = columnIndexMap["StartTime"]
                ?: throw IllegalStateException("StartTime column not found in CSV")

            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

            val cutoffTime = if (EvaluationState.isEvaluationMode.value) {
                val evalDate = EvaluationState.EVALUATION_DATE
                val currentTime = LocalDateTime.now()
                LocalDateTime.of(evalDate, currentTime.toLocalTime())
            } else {
                LocalDateTime.now()
            }

            Log.i(TAG, "Historical cutoff time: $cutoffTime")
            Log.i(TAG, "Evaluation mode: ${EvaluationState.isEvaluationMode.value}")

            // Reset cumulative trackers
            cumulativeSteps = 0
            cumulativeEnergy = 0.0
            lastKnownCyclePhase = ""
            lastKnownCycleDay = 0

            val historicalRecords = dataLines.mapIndexedNotNull { index, line ->
                try {
                    val values = line.split(',')
                    val recordTime = LocalDateTime.parse(values[startTimeIndex].trim(), formatter)

                    if (recordTime.isBefore(cutoffTime) || recordTime.isEqual(cutoffTime)) {
                        val entity = parseRecordToEntity(values, columnIndexMap, recordTime)

                        // Track maximum cumulative values
                        if (entity.stepCount > cumulativeSteps) {
                            cumulativeSteps = entity.stepCount
                        }
                        if (entity.activeEnergyBurned > cumulativeEnergy) {
                            cumulativeEnergy = entity.activeEnergyBurned
                        }

                        // Track cycle info
                        if (entity.cyclePhase.isNotEmpty()) {
                            lastKnownCyclePhase = entity.cyclePhase
                            lastKnownCycleDay = entity.cycleDay
                        }

                        entity
                    } else null
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing line $index: $line", e)
                    null
                }
            }

            Log.i(TAG, "Parsed ${historicalRecords.size} historical records")
            Log.i(TAG, "Final cumulative values: Steps=$cumulativeSteps, Energy=${cumulativeEnergy.toInt()}")
            Log.i(TAG, "Final cycle: $lastKnownCyclePhase Day $lastKnownCycleDay")

            if (historicalRecords.isEmpty()) {
                Log.w(TAG, "❌ No historical records found!")
                _historicalLoadingProgress.value = 1f
                _isHistoricalLoadingComplete.value = true
                return
            }

            // Sort by timestamp
            val sortedRecords = historicalRecords.sortedBy { it.timestamp }

            // Batch insert with progress updates
            val batchSize = 100
            val batches = sortedRecords.chunked(batchSize)

            Log.i(TAG, "Inserting ${batches.size} batches into database...")

            batches.forEachIndexed { batchIndex, batch ->
                repository.insertBatch(batch)

                // Emit snapshot periodically
                if (batchIndex % 5 == 0 || batchIndex == batches.size - 1) {
                    val lastRecordInBatch = batch.last()

                    val snapshot = HealthDataSnapshot(
                        timestamp = lastRecordInBatch.timestamp,
                        heartRate = lastRecordInBatch.heartRate,
                        vo2Max = lastRecordInBatch.vo2Max,
                        restingHeartRate = lastRecordInBatch.restingHeartRate,
                        basalBodyTemperature = lastRecordInBatch.basalBodyTemperature,
                        basalEnergyBurned = lastRecordInBatch.basalEnergyBurned,
                        cyclePhase = lastKnownCyclePhase,
                        cycleDay = lastKnownCycleDay,
                        cumulativeSteps = cumulativeSteps,
                        cumulativeEnergyBurned = cumulativeEnergy,
                        isHistoricalDataLoaded = batchIndex == batches.size - 1,
                        dataSource = DataSource.HISTORICAL
                    )

                    HealthDataStreamManager.emitData(snapshot)

                    Log.d(TAG, "Batch $batchIndex/${batches.size} complete, emitted snapshot")
                }

                val progress = ((batchIndex + 1).toFloat() / batches.size)
                _historicalLoadingProgress.value = progress

                if (batchIndex % 10 == 0) {
                    Log.d(TAG, "Progress: ${(progress * 100).toInt()}%")
                }
            }

            // Mark as loaded in DataStore
            HistoricalDataManager.markHistoricalDataLoaded(
                context = context,
                cutoffTime = cutoffTime,
                recordCount = historicalRecords.size.toLong(),
                isEvaluationMode = EvaluationState.isEvaluationMode.value
            )

            _historicalLoadingProgress.value = 1f
            _isHistoricalLoadingComplete.value = true

            Log.i(TAG, "✓ Historical data load complete and persisted!")
            Log.i(TAG, "  Next launch will load from database (much faster)")

        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to load historical data", e)
            e.printStackTrace()
            _isHistoricalLoadingComplete.value = true
        }
    }

    /**
     * Heartbeat to keep data fresh
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && _simulationState.value.isRunning) {
                try {
                    delay(HEARTBEAT_INTERVAL_MS)

                    val currentSnapshot = HealthDataStreamManager.getCurrentSnapshot()
                    val heartbeatSnapshot = currentSnapshot.copy(
                        timestamp = System.currentTimeMillis()
                    )

                    HealthDataStreamManager.emitData(heartbeatSnapshot)

                    Log.v(TAG, "Heartbeat: Steps=${heartbeatSnapshot.cumulativeSteps}, " +
                            "HR=${heartbeatSnapshot.heartRate.toInt()}, " +
                            "Cycle=${heartbeatSnapshot.cyclePhase} D${heartbeatSnapshot.cycleDay}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error in heartbeat", e)
                }
            }
        }
        Log.i(TAG, "✓ Heartbeat started (${HEARTBEAT_INTERVAL_MS}ms interval)")
    }

    /**
     * Run live simulation (after historical data)
     */
    private suspend fun runLiveSimulation(context: Context, csvFileName: String) {
        try {
            Log.i(TAG, "Starting live simulation loop...")

            val allLines = context.assets.open(csvFileName).bufferedReader().readLines()
            val header = allLines.first()
            val dataLines = allLines.drop(1)

            val columnIndexMap = header.split(',')
                .mapIndexed { index, name -> name.trim() to index }
                .toMap()

            val startTimeIndex = columnIndexMap["StartTime"] ?: return
            val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

            val cutoffTime = if (EvaluationState.isEvaluationMode.value) {
                val evalDate = EvaluationState.EVALUATION_DATE
                val currentTime = LocalDateTime.now()
                LocalDateTime.of(evalDate, currentTime.toLocalTime())
            } else {
                LocalDateTime.now()
            }

            // Filter live records (after cutoff)
            val liveRecords = dataLines.mapNotNull { line ->
                try {
                    val values = line.split(',')
                    val recordTime = LocalDateTime.parse(values[startTimeIndex].trim(), formatter)

                    if (recordTime.isAfter(cutoffTime)) {
                        Pair(values, recordTime)
                    } else null
                } catch (e: Exception) {
                    null
                }
            }

            Log.i(TAG, "Live simulation: ${liveRecords.size} records to stream")

            _simulationState.value = SimulationState(
                isRunning = true,
                isPaused = false,
                totalRecords = liveRecords.size
            )

            var previousTime: LocalDateTime? = null

            liveRecords.forEachIndexed { index, (values, recordTime) ->
                // Check pause state
                while (_simulationState.value.isPaused) {
                    delay(1000)
                }

                // Check if stopped
                if (!_simulationState.value.isRunning) {
                    Log.i(TAG, "Simulation stopped")
                    return@runLiveSimulation
                }

                // Calculate delay
                val delayMs = if (previousTime != null) {
                    ChronoUnit.MILLIS.between(previousTime, recordTime)
                        .coerceIn(100L, 60000L)
                } else {
                    1000L
                }

                delay(delayMs)

                // Parse record
                val record = parseHealthDataRecord(values, columnIndexMap)

                // Update cumulative values (take max)
                if (record.stepCount > cumulativeSteps) {
                    cumulativeSteps = record.stepCount
                }
                if (record.activeEnergyBurned > cumulativeEnergy) {
                    cumulativeEnergy = record.activeEnergyBurned
                }

                if (record.cyclePhase.isNotEmpty()) {
                    lastKnownCyclePhase = record.cyclePhase
                    lastKnownCycleDay = record.cycleDay
                }

                val snapshot = HealthDataSnapshot(
                    timestamp = System.currentTimeMillis(),
                    heartRate = record.heartRate,
                    vo2Max = record.vo2Max,
                    restingHeartRate = record.restingHeartRate,
                    basalBodyTemperature = record.basalBodyTemperature,
                    basalEnergyBurned = record.basalEnergyBurned,
                    cyclePhase = lastKnownCyclePhase,
                    cycleDay = lastKnownCycleDay,
                    cumulativeSteps = cumulativeSteps,
                    cumulativeEnergyBurned = cumulativeEnergy,
                    isHistoricalDataLoaded = true,
                    dataSource = DataSource.SIMULATION
                )

                HealthDataStreamManager.emitData(snapshot)

                val entity = HealthRecordEntity(
                    timestamp = snapshot.timestamp,
                    heartRate = snapshot.heartRate,
                    stepCount = snapshot.cumulativeSteps,
                    activeEnergyBurned = snapshot.cumulativeEnergyBurned,
                    basalEnergyBurned = snapshot.basalEnergyBurned,
                    cyclePhase = snapshot.cyclePhase,
                    cycleDay = snapshot.cycleDay,
                    vo2Max = snapshot.vo2Max,
                    restingHeartRate = snapshot.restingHeartRate,
                    basalBodyTemperature = snapshot.basalBodyTemperature
                )
                repository.insertHealthRecord(entity)

                _simulationState.value = _simulationState.value.copy(
                    currentIndex = index,
                    lastUpdateTime = System.currentTimeMillis()
                )

                previousTime = recordTime

                if (index % 100 == 0) {
                    Log.d(TAG, "Live simulation progress: $index/${liveRecords.size}")
                }
            }

            Log.i(TAG, "Live simulation complete")
            _simulationState.value = _simulationState.value.copy(isRunning = false)

        } catch (e: Exception) {
            Log.e(TAG, "Error in live simulation", e)
            e.printStackTrace()
            _simulationState.value = _simulationState.value.copy(isRunning = false)
        }
    }

    fun stopStreaming() {
        simulationJob?.cancel()
        heartbeatJob?.cancel()
        simulationJob = null
        heartbeatJob = null
        _simulationState.value = SimulationState()
        _isDataAccessible.value = false
        Log.i(TAG, "Simulation and heartbeat stopped")
    }

    fun pauseSimulation() {
        _simulationState.value = _simulationState.value.copy(isPaused = true)
        Log.i(TAG, "Simulation paused")
    }

    fun resumeSimulation() {
        _simulationState.value = _simulationState.value.copy(isPaused = false)
        Log.i(TAG, "Simulation resumed")
    }

    /**
     * Parse CSV record to HealthRecordEntity with proper null handling
     */
    private fun parseRecordToEntity(
        values: List<String>,
        columnIndexMap: Map<String, Int>,
        recordTime: LocalDateTime
    ): HealthRecordEntity {
        val timestamp = recordTime.atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

        fun getDouble(columnName: String): Double {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull() ?: 0.0
        }

        fun getInt(columnName: String): Int {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntOrNull() ?: 0
        }

        fun getString(columnName: String): String {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim() ?: ""
        }

        return HealthRecordEntity(
            timestamp = timestamp,
            heartRate = getDouble("HeartRate"),
            stepCount = getInt("StepCount"),
            activeEnergyBurned = getDouble("ActiveEnergyBurned"),
            basalEnergyBurned = getDouble("BasalEnergyBurned"),
            cyclePhase = getString("MenstrualCyclePhase"),
            cycleDay = getInt("MenstrualCycle Day"),
            vo2Max = getDouble("VO2Max"),
            restingHeartRate = getDouble("RestingHeartRate"),
            basalBodyTemperature = getDouble("BasalBodyTemperature")
        )
    }

    private fun parseHealthDataRecord(
        values: List<String>,
        columnIndexMap: Map<String, Int>
    ): HealthDataRecord {
        fun getDouble(columnName: String): Double {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toDoubleOrNull() ?: 0.0
        }

        fun getInt(columnName: String): Int {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntOrNull() ?: 0
        }

        fun getString(columnName: String): String {
            return values.getOrNull(columnIndexMap[columnName] ?: -1)
                ?.trim() ?: ""
        }

        return HealthDataRecord(
            heartRate = getDouble("HeartRate"),
            stepCount = getInt("StepCount"),
            activeEnergyBurned = getDouble("ActiveEnergyBurned"),
            basalEnergyBurned = getDouble("BasalEnergyBurned"),
            cyclePhase = getString("MenstrualCyclePhase"),
            cycleDay = getInt("MenstrualCycle Day"),
            vo2Max = getDouble("VO2Max"),
            restingHeartRate = getDouble("RestingHeartRate"),
            basalBodyTemperature = getDouble("BasalBodyTemperature")
        )
    }
}