package com.example.honoursproject.dataservices

/**
 * Represents a single record of health data parsed from the CSV.
 * Default values are provided for the initial state before streaming begins.
 */
data class HealthDataRecord(
    val heartRate: Double = 0.0,
    val stepCount: Int = 0,
    val activeEnergyBurned: Double = 0.0,
    val basalEnergyBurned: Double = 0.0,
    val cyclePhase: String = "Unknown",
    val cycleDay: Int = 0,
    val vo2Max: Double = 0.0,
    val restingHeartRate: Double = 0.0,
    val basalBodyTemperature: Double = 0.0
)