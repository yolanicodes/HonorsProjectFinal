package com.example.honoursproject.dataservices

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

// Create DataStore delegate on Context
val Context.simulationDataStore by preferencesDataStore(name = "simulation_prefs")

object SimulationManager {

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
                val systemNow = LocalDateTime.now()

                var bestMatchIdx = -1
                dataLines.forEachIndexed { idx, line ->
                    val parts = line.split(',')
                    try {
                        val originalRecordTime = LocalDateTime.parse(parts[startTimeIndex], formatter)
                        val dateMatches = originalRecordTime.dayOfMonth == systemNow.dayOfMonth &&
                                originalRecordTime.monthValue == systemNow.monthValue
                        if (dateMatches) {
                            val recordTimeForComparison = originalRecordTime.withYear(systemNow.year)
                            if (!recordTimeForComparison.isAfter(systemNow)) {
                                bestMatchIdx = idx
                            }
                        }
                    } catch (_: Exception) { }
                }
                if (bestMatchIdx == -1) {
                    Log.w("SimulationManager", "No matching record for today. Falling back.")
                    dataLines.forEachIndexed { idx, line ->
                        val parts = line.split(',')
                        try {
                            val recordTime = LocalDateTime.parse(parts[startTimeIndex], formatter)
                                .withYear(systemNow.year)
                            if (!recordTime.isAfter(systemNow)) {
                                bestMatchIdx = idx
                            }
                        } catch (_: Exception) { }
                    }
                }

                currentIndex = if (bestMatchIdx >= 0) bestMatchIdx else 0
                Log.i("SimulationManager", "Using index $currentIndex")

                var previousRecordTime: LocalDateTime? = null
                try {
                    val initialLine = dataLines[currentIndex]
                    val initialValues = initialLine.split(',')
                    val initialTime = LocalDateTime.parse(initialValues[startTimeIndex], formatter)
                    val initialRecord = parseRecord(initialValues, columnIndexMap, lastKnownRecord)

                    currentSimulationDay = initialTime.dayOfMonth
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

                    val currentRecordTime = try {
                        LocalDateTime.parse(values[startTimeIndex], formatter)
                            .withYear(LocalDateTime.now().year)
                            .withMonth(LocalDateTime.now().monthValue)
                            .withDayOfMonth(LocalDateTime.now().dayOfMonth)
                    } catch (e: Exception) {
                        Log.e("SimulationManager", "Error parsing time", e)
                        null
                    }

                    if (currentRecordTime != null) {
                        if (currentRecordTime.dayOfMonth != currentSimulationDay) {
                            currentSimulationDay = currentRecordTime.dayOfMonth
                            cumulativeSteps = 0
                            cumulativeEnergy = 0.0
                            Log.i("SimulationManager", "New day detected. Resetting cumulative totals.")
                        }

                        val delayMillis = if (previousRecordTime == null) 0L else
                            ChronoUnit.MILLIS.between(previousRecordTime, currentRecordTime).coerceAtLeast(0)
                        Log.d("SimulationManager", "Calculated delay: $delayMillis ms")
                        delay(delayMillis)

                        if (isActive) {
                            val newRecord = parseRecord(values, columnIndexMap, lastKnownRecord)

                            // Track cumulative values separately
                            val stepDifference = newRecord.stepCount - lastKnownRecord.stepCount
                            if (stepDifference > 0) cumulativeSteps += stepDifference

                            val energyDifference = newRecord.activeEnergyBurned - lastKnownRecord.activeEnergyBurned
                            if (energyDifference > 0) cumulativeEnergy += energyDifference

                            // Emit raw record (not cumulative)
                            _healthDataStream.value = newRecord
                            lastKnownRecord = newRecord
                            previousRecordTime = currentRecordTime

                            val timestamp = System.currentTimeMillis()
                            Log.i("SimulationManager", "Emitting new record -> HR: ${newRecord.heartRate}, Steps: ${newRecord.stepCount}")

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
