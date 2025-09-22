package com.example.honoursproject.dataservices

data class DailyAggregateRaw(
    val date: String,
    val avgHeartRate: Double,
    val maxSteps: Int,
    val totalEnergy: Double,
    val cyclePhase: String,
    val cycleDay: Int
)
