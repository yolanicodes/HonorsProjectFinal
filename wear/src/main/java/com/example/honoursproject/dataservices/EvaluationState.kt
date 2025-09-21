package com.example.honoursproject.dataservices

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

object EvaluationState {
    val EVALUATION_DATE: LocalDate = LocalDate.of(2023, 8, 19)

    private val _isEvaluationMode = MutableStateFlow(false)
    val isEvaluationMode = _isEvaluationMode.asStateFlow()
    fun startEvaluationMode() {
        _isEvaluationMode.value = true
    }

    fun stopEvaluationMode() {
        _isEvaluationMode.value = false
    }
}