package com.example.honoursproject.dataservices

import java.time.LocalDate
import java.time.ZoneId

object EvaluationDateHelper {

    fun dateToTimestamp(date: LocalDate, atStartOfDay: Boolean = true): Long {
        return if (atStartOfDay) {
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }

    fun getHistoricalRange(): Pair<Long, Long> {
        val startDate = LocalDate.of(2023, 8, 1)
        val endDate = LocalDate.of(2023, 8, 12)
        return Pair(
            dateToTimestamp(startDate, true),
            dateToTimestamp(endDate, false)
        )
    }

    fun getCurrentDayRange(): Pair<Long, Long> {
        val currentDate = EvaluationState.EVALUATION_DATE
        return Pair(
            dateToTimestamp(currentDate, true),
            dateToTimestamp(currentDate, false)
        )
    }

    fun getDataRange(forHistorical: Boolean): Pair<Long, Long> {
        return if (EvaluationState.isEvaluationMode.value) {
            if (forHistorical) getHistoricalRange() else getCurrentDayRange()
        } else {
            val now = System.currentTimeMillis()
            val weekAgo = now - (7 * 24 * 60 * 60 * 1000L)
            Pair(weekAgo, now)
        }
    }
}