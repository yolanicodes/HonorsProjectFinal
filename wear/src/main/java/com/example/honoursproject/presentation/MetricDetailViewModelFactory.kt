package com.example.honoursproject.presentation

// MetricDetailViewModelFactory.kt (Create this new file)
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.honoursproject.dataservices.SimulationManager

class MetricDetailViewModelFactory(
    private val metricType: String,
    private val context: Context
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MetricDetailViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MetricDetailViewModel(
                metricType,
                SimulationManager,
                context
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}