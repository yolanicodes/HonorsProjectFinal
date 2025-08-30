/*package com.example.honoursproject.presentation

import android.content.Context
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.example.honoursproject.dataservices.SimulationManager

/**
 * Factory for creating ViewModels that depend on the SimulationService and SavedStateHandle.
 */
class ViewModelFactory(
    owner: SavedStateRegistryOwner,
    private val context: Context
) : AbstractSavedStateViewModelFactory(owner, null) {

    override fun <T : ViewModel> create(
        key: String,
        modelClass: Class<T>,
        handle: SavedStateHandle
    ): T {

        // Since SimulationManager is a singleton object, we just use it directly.
        val simulationManager = SimulationManager

        return when {
            modelClass.isAssignableFrom(MetricDetailViewModel::class.java) -> {
                MetricDetailViewModel(handle, simulationManager, context.applicationContext) as T
            }
//            modelClass.isAssignableFrom(WorkoutViewModel::class.java) -> {
//                WorkoutViewModel(simulationManager, context.applicationContext) as T
//            }
            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
*/