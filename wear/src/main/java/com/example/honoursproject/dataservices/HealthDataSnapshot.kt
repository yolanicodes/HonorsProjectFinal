package com.example.honoursproject.dataservices

data class HealthDataSnapshot(
// Atomic values (instantaneous measurements)
    val timestamp: Long = System.currentTimeMillis(),
    val heartRate: Double = 0.0,
    val vo2Max: Double = 0.0,
    val restingHeartRate: Double = 0.0,
    val basalBodyTemperature: Double = 0.0,
    val basalEnergyBurned: Double = 0.0,
    val cyclePhase: String = "",
    val cycleDay: Int = 0,
// Aggregate values (cumulative for the day)
    val cumulativeSteps: Int = 0,
    val cumulativeEnergyBurned: Double = 0.0,
// Metadata
    val isHistoricalDataLoaded: Boolean = false,
    val dataSource: DataSource = DataSource.SIMULATION
)
enum class DataSource {
    SIMULATION, // Live simulation data
    DATABASE, // Loaded from database
    HISTORICAL // Historical pre-load
}