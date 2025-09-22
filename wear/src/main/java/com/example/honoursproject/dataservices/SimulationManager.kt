package com.example.honoursproject.dataservices

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

// Create DataStore delegate on Context
val Context.simulationDataStore by preferencesDataStore(name = "simulation_prefs")

object SimulationManager {

    // Hardcoded max heart rate as requested.
    const val MAX_HEART_RATE = 220

    private val KEY_INDEX = intPreferencesKey("csv_index")

    private val _healthDataStream = MutableStateFlow(HealthDataRecord())
    val healthDataStream = _healthDataStream.asStateFlow()

    private var simulationJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cumulativeSteps = 0
    private var cumulativeEnergy = 0.0
    private var currentSimulationDay: Int = -1
    private var lastKnownRecord = HealthDataRecord()
    private var currentIndex: Int = 0
    private var dataLines: List<String> = emptyList()

    suspend fun getUserAge(context: Context): Int {
        return context.setupDataStore.data
            .map { preferences ->
                preferences[SetupPreferences.AGE] ?: 18
            }
            .first()
    }

    fun startStreaming(context: Context, csvFileName: String) {
        if (simulationJob?.isActive == true) return

        simulationJob = scope.launch {
            try {
                cumulativeSteps = 0
                cumulativeEnergy = 0.0
                val allLines = context.assets.open(csvFileName).bufferedReader().readLines()
                if (allLines.size <= 1) {
                    println("Only header is being read.")
                    return@launch
                }

                // UPDATED: Enhanced evaluation mode logic
                val (targetDate, historicalStartDate, historicalEndDate) = if (EvaluationState.isEvaluationMode.value) {
                    Triple(
                        EvaluationState.EVALUATION_DATE, // Current day: 2023-08-19
                        LocalDate.of(2023, 8, 1),       // Historical start: 2023-08-01
                        LocalDate.of(2023, 8, 12)       // Historical end: 2023-08-12
                    )
                } else {
                    Triple(LocalDate.now(), null, null)
                }

                Log.i("SimulationManager", "Target date: $targetDate, Historical range: $historicalStartDate to $historicalEndDate (Evaluation Mode: ${EvaluationState.isEvaluationMode.value})")

                // Load historical data if in evaluation mode
                if (EvaluationState.isEvaluationMode.value && historicalStartDate != null && historicalEndDate != null) {
                    loadHistoricalData(context, allLines, historicalStartDate, historicalEndDate)
                }

                val lastIndex = withContext(Dispatchers.IO) {
                    context.simulationDataStore.data
                        .map { prefs -> prefs[KEY_INDEX] ?: 0 }
                        .first()
                }
                currentIndex = lastIndex.coerceAtMost(allLines.size - 1)
                Log.i("SimulationManager", "Starting/resuming simulation from index: $currentIndex")

                val header = allLines.first()
                dataLines = allLines.drop(1)
                val columnIndexMap = header.split(',')
                    .mapIndexed { index, name -> name.trim() to index }.toMap()
                val startTimeIndex = columnIndexMap["StartTime"] ?: return@launch

                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

                // UPDATED: Find matching records for current day with proper time mapping
                val bestMatchIdx = findBestMatchingRecord(dataLines, startTimeIndex, formatter, targetDate)

                currentIndex = if (bestMatchIdx >= 0) bestMatchIdx else 0
                Log.i("SimulationManager", "Using index $currentIndex for date $targetDate")

                // Set initial baseline
                var previousRecordTime: LocalDateTime? = null
                try {
                    val initialLine = dataLines[currentIndex]
                    val initialValues = initialLine.split(',')
                    val initialTime = LocalDateTime.parse(initialValues[startTimeIndex], formatter)
                    val initialRecord = parseRecord(initialValues, columnIndexMap, lastKnownRecord)

                    currentSimulationDay = if (EvaluationState.isEvaluationMode.value) {
                        targetDate.dayOfMonth
                    } else {
                        initialTime.dayOfMonth
                    }
                    Log.i("SimulationManager", "Initial baseline set for day $currentSimulationDay")
                } catch (e: Exception) {
                    Log.e("SimulationManager", "Failed to set initial baseline", e)
                }

                val shortTermDb = HealthDatabase.getInstance(context)
                val shortTermDao = shortTermDb.healthRecordDao()
                val archiveDb = ArchiveDatabase.getInstance(context)
                val archiveDao = archiveDb.archiveRecordDao()

                while (isActive) {
                    val line = dataLines[currentIndex]
                    Log.d("SimulationManager", "Processing line (index $currentIndex): $line")
                    val values = line.split(',')

                    // UPDATED: Map CSV timestamps to current system time in evaluation mode
                    val currentRecordTime = try {
                        val csvTime = LocalDateTime.parse(values[startTimeIndex], formatter)
                        if (EvaluationState.isEvaluationMode.value) {
                            // Map the CSV date to current system date but keep the time
                            val systemNow = LocalDateTime.now()
                            LocalDateTime.of(
                                systemNow.toLocalDate(),
                                csvTime.toLocalTime()
                            )
                        } else {
                            // Normal mode: map to current date
                            csvTime.withYear(LocalDateTime.now().year)
                                .withMonth(LocalDateTime.now().monthValue)
                                .withDayOfMonth(LocalDateTime.now().dayOfMonth)
                        }
                    } catch (e: Exception) {
                        Log.e("SimulationManager", "Error parsing time", e)
                        null
                    }

                    if (currentRecordTime != null) {
                        // Reset cumulative values for new day
                        val recordDay = if (EvaluationState.isEvaluationMode.value) {
                            targetDate.dayOfMonth
                        } else {
                            currentRecordTime.dayOfMonth
                        }

                        if (recordDay != currentSimulationDay) {
                            currentSimulationDay = recordDay
                            cumulativeSteps = 0
                            cumulativeEnergy = 0.0
                            Log.i("SimulationManager", "New day detected. Resetting cumulative totals.")
                        }

                        val delayMillis = if (previousRecordTime == null) {
                            0L
                        } else {
                            ChronoUnit.MILLIS.between(previousRecordTime, currentRecordTime).coerceAtLeast(0)
                        }

                        Log.d("SimulationManager", "Calculated delay: $delayMillis ms")
                        delay(delayMillis)

                        if (isActive) {
                            val newRecord = parseRecord(values, columnIndexMap, lastKnownRecord)

                            // Track cumulative values
                            val stepDifference = newRecord.stepCount - lastKnownRecord.stepCount
                            if (stepDifference > 0) cumulativeSteps += stepDifference

                            val energyDifference = newRecord.activeEnergyBurned - lastKnownRecord.activeEnergyBurned
                            if (energyDifference > 0) cumulativeEnergy += energyDifference

                            // Emit record with current timestamp
                            _healthDataStream.value = newRecord
                            lastKnownRecord = newRecord
                            previousRecordTime = currentRecordTime

                            val timestamp = System.currentTimeMillis()
                            Log.i("SimulationManager", "Emitting record -> HR: ${newRecord.heartRate}, Steps: ${newRecord.stepCount}, Time: $currentRecordTime")

                            // Store in databases with current timestamp
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
                                    val cutoff = timestamp - TimeUnit.HOURS.toMillis(3)
                                    shortTermDao.deleteOlderThan(cutoff)
                                } catch (e: Exception) {
                                    Log.e("SimulationManager", "Failed to insert record", e)
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
                            context.simulationDataStore.edit { prefs -> prefs[KEY_INDEX] = currentIndex }
                        }
                    }
                    currentIndex = (currentIndex + 1) % dataLines.size
                    if (currentIndex == 0) previousRecordTime = null
                }
            } catch (e: IOException) {
                Log.e("SimulationManager", "Error reading CSV", e)
            }
        }
    }

    // UPDATED: Enhanced record matching for evaluation mode
    private fun findBestMatchingRecord(
        dataLines: List<String>,
        startTimeIndex: Int,
        formatter: DateTimeFormatter,
        targetDate: LocalDate
    ): Int {
        val systemNow = LocalDateTime.now()
        var bestMatchIdx = -1

        dataLines.forEachIndexed { idx, line ->
            val parts = line.split(',')
            try {
                val originalRecordTime = LocalDateTime.parse(parts[startTimeIndex], formatter)

                if (originalRecordTime.toLocalDate().isEqual(targetDate)) {
                    // In evaluation mode, find the record closest to current system time
                    if (EvaluationState.isEvaluationMode.value) {
                        // Find the first record that hasn't "happened" yet based on system time
                        if (!originalRecordTime.toLocalTime().isAfter(systemNow.toLocalTime())) {
                            bestMatchIdx = idx
                        }
                    } else {
                        // Normal mode logic
                        if (!originalRecordTime.toLocalTime().isAfter(systemNow.toLocalTime())) {
                            bestMatchIdx = idx
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        if (bestMatchIdx == -1) {
            Log.w("SimulationManager", "No matching record found for $targetDate. Using first record of that date.")
            bestMatchIdx = dataLines.indexOfFirst { line ->
                try {
                    LocalDateTime.parse(line.split(',')[startTimeIndex], formatter)
                        .toLocalDate().isEqual(targetDate)
                } catch (e: Exception) { false }
            }.takeIf { it != -1 } ?: 0
        }

        return bestMatchIdx
    }

    // NEW: Load historical data for evaluation mode
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

        // Filter records within historical date range
        val historicalRecords = dataLines.filter { line ->
            try {
                val recordTime = LocalDateTime.parse(line.split(',')[startTimeIndex], formatter)
                val recordDate = recordTime.toLocalDate()
                !recordDate.isBefore(startDate) && !recordDate.isAfter(endDate)
            } catch (e: Exception) {
                false
            }
        }

        Log.i("SimulationManager", "Found ${historicalRecords.size} historical records")

        // Insert historical records with timestamps mapping to past dates
        historicalRecords.forEach { line ->
            try {
                val values = line.split(',')
                val originalTime = LocalDateTime.parse(values[startTimeIndex], formatter)
                val record = parseRecord(values, columnIndexMap, HealthDataRecord())

                // Calculate timestamp for historical date
                val historicalTimestamp = originalTime.atZone(java.time.ZoneId.systemDefault())
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
            } catch (e: Exception) {
                Log.e("SimulationManager", "Error inserting historical record", e)
            }
        }

        Log.i("SimulationManager", "Historical data loading completed")
    }

    fun stopStreaming() {
        simulationJob?.cancel()
        simulationJob = null
    }

    fun getCumulativeSteps(): Int = cumulativeSteps
    fun getCumulativeEnergy(): Double = cumulativeEnergy

    private fun parseRecord(
        values: List<String>,
        columnIndexMap: Map<String, Int>,
        lastRecord: HealthDataRecord
    ): HealthDataRecord {
        val hrIndex = columnIndexMap["HeartRate"]
        val stepsIndex = columnIndexMap["StepCount"]
        val activeEnergyIndex = columnIndexMap["ActiveEnergyBurned"]
        val basalEnergyIndex = columnIndexMap["BasalEnergyBurned"]
        val cyclePhaseIndex = columnIndexMap["MenstrualCyclePhase"]
        val cycleDayIndex = columnIndexMap["MenstrualCycle Day"]
        val vo2MaxIndex = columnIndexMap["VO2Max"]
        val restingHrIndex = columnIndexMap["RestingHeartRate"]
        val bbtIndex = columnIndexMap["BasalBodyTemperature"]

        return HealthDataRecord(
            heartRate = hrIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.heartRate,
            stepCount = stepsIndex?.let { values.getOrNull(it)?.toIntOrNull() } ?: lastRecord.stepCount,
            activeEnergyBurned = activeEnergyIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.activeEnergyBurned,
            basalEnergyBurned = basalEnergyIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.basalEnergyBurned,
            cyclePhase = cyclePhaseIndex?.let { values.getOrNull(it)?.takeIf { it.isNotBlank() } } ?: lastRecord.cyclePhase,
            cycleDay = cycleDayIndex?.let { values.getOrNull(it)?.toIntOrNull() } ?: lastRecord.cycleDay,
            vo2Max = vo2MaxIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.vo2Max,
            restingHeartRate = restingHrIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.restingHeartRate,
            basalBodyTemperature = bbtIndex?.let { values.getOrNull(it)?.toDoubleOrNull() } ?: lastRecord.basalBodyTemperature
        )
    }
}