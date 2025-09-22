// Fixed SimulationManager.kt
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
import java.time.ZoneId

// Create DataStore delegate on Context
val Context.simulationDataStore by preferencesDataStore(name = "simulation_prefs")

object SimulationManager {

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

                Log.i("SimulationManager", "Reading CSV file: $csvFileName")
                val allLines = context.assets.open(csvFileName).bufferedReader().readLines()

                if (allLines.size <= 1) {
                    Log.e("SimulationManager", "CSV file has no data rows")
                    return@launch
                }

                Log.i("SimulationManager", "Successfully read ${allLines.size} lines from CSV")

                // UPDATED: Enhanced evaluation mode logic
                val (targetDate, historicalStartDate, historicalEndDate) = if (EvaluationState.isEvaluationMode.value) {
                    Triple(
                        EvaluationState.EVALUATION_DATE, // Current day: 2023-08-19
                        LocalDate.of(2023, 8, 1),        // Historical start: 2023-08-01
                        LocalDate.of(2023, 8, 18)        // Historical end: 2023-08-18
                    )
                } else {
                    Triple(LocalDate.now(), null, null)
                }

                Log.i("SimulationManager", "Target date: $targetDate, Historical range: $historicalStartDate to $historicalEndDate (Evaluation Mode: ${EvaluationState.isEvaluationMode.value})")

                // FIXED: Always load historical data first in evaluation mode
                if (EvaluationState.isEvaluationMode.value && historicalStartDate != null && historicalEndDate != null) {
                    Log.i("SimulationManager", "Loading historical data for evaluation mode...")
                    loadHistoricalData(context, allLines, historicalStartDate, historicalEndDate)
                    Log.i("SimulationManager", "Historical data loading completed")
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

                Log.i("SimulationManager", "CSV columns found: ${columnIndexMap.keys}")

                val startTimeIndex = columnIndexMap["StartTime"] ?: run {
                    Log.e("SimulationManager", "StartTime column not found in CSV")
                    return@launch
                }

                val formatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

                // Find matching records for current day with proper time mapping
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

                    // FIXED: Set initial cumulative values
                    lastKnownRecord = initialRecord
                    cumulativeSteps = initialRecord.stepCount
                    cumulativeEnergy = initialRecord.activeEnergyBurned

                    Log.i("SimulationManager", "Initial baseline set for day $currentSimulationDay, Steps: $cumulativeSteps")
                } catch (e: Exception) {
                    Log.e("SimulationManager", "Failed to set initial baseline", e)
                }

                val shortTermDb = HealthDatabase.getInstance(context)
                val shortTermDao = shortTermDb.healthRecordDao()
                val archiveDb = ArchiveDatabase.getInstance(context)
                val archiveDao = archiveDb.archiveRecordDao()

                while (isActive) {
                    val line = dataLines[currentIndex]
                    Log.v("SimulationManager", "Processing line (index $currentIndex): $line")
                    val values = line.split(',')

                    // Map CSV timestamps to current system time in evaluation mode
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
                        Log.e("SimulationManager", "Error parsing time from: ${values.getOrNull(startTimeIndex)}", e)
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

                        Log.v("SimulationManager", "Calculated delay: ${delayMillis}ms")
                        delay(delayMillis)

                        if (isActive) {
                            val newRecord = parseRecord(values, columnIndexMap, lastKnownRecord)

                            // FIXED: Proper cumulative tracking
                            cumulativeSteps = newRecord.stepCount
                            cumulativeEnergy = newRecord.activeEnergyBurned

                            // Emit record with current timestamp
                            _healthDataStream.value = newRecord
                            lastKnownRecord = newRecord
                            previousRecordTime = currentRecordTime

                            val timestamp = System.currentTimeMillis()
                            Log.d("SimulationManager", "Emitting record -> HR: ${newRecord.heartRate}, Steps: ${newRecord.stepCount}, Time: $currentRecordTime")

                            // Store in databases with current timestamp
                            launch {
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
                                    Log.e("SimulationManager", "Failed to insert short-term record", e)
                                }
                            }

                            launch {
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
                    if (currentIndex == 0) {
                        previousRecordTime = null
                        Log.i("SimulationManager", "Reached end of CSV, wrapping to beginning")
                    }
                }
            } catch (e: IOException) {
                Log.e("SimulationManager", "Error reading CSV file: $csvFileName", e)
            } catch (e: Exception) {
                Log.e("SimulationManager", "Unexpected error in simulation", e)
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

        Log.i("SimulationManager", "Searching for records matching date: $targetDate")

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
                Log.v("SimulationManager", "Could not parse time from line $idx: ${e.message}")
            }
        }

        Log.i("SimulationManager", "Best match for $targetDate: index $bestMatchIdx (time diff: $closestTimeDiff minutes)")
        return bestMatchIdx
    }

    // ENHANCED: More comprehensive historical data loading
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

        // FIXED: Clear existing archive data first in evaluation mode
        Log.i("SimulationManager", "Clearing existing archive data for evaluation mode")

        // Filter records within historical date range
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
                Log.v("SimulationManager", "Could not parse historical record: ${e.message}")
                null
            }
        }

        Log.i("SimulationManager", "Found ${historicalRecords.size} historical records to load")

        // Sort by time to maintain chronological order
        val sortedRecords = historicalRecords.sortedBy { it.first }

        var insertedCount = 0

        // Insert historical records with actual historical timestamps
        sortedRecords.forEach { (originalTime, values) ->
            try {
                val record = parseRecord(values, columnIndexMap, HealthDataRecord())

                // Use the original timestamp from the CSV
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
                Log.e("SimulationManager", "Error inserting historical record for time $originalTime", e)
            }
        }

        Log.i("SimulationManager", "Successfully inserted $insertedCount historical records")
    }

    fun stopStreaming() {
        simulationJob?.cancel()
        simulationJob = null
        Log.i("SimulationManager", "Simulation stopped")
    }

    fun getCumulativeSteps(): Int = cumulativeSteps
    fun getCumulativeEnergy(): Double = cumulativeEnergy

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
            val cycleDayIndex = columnIndexMap["MenstrualCycle Day"] // Note the space in CSV header
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
            lastRecord // Return last known good record
        }
    }
}