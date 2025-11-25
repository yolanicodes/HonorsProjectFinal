package com.example.honoursproject.dataservices

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates navigation between activities to prevent race conditions
 * and duplicate launches
 */
object NavigationCoordinator {

    private const val TAG = "NavigationCoordinator"

    // Track active activities to prevent duplicates
    private val activeActivities = ConcurrentHashMap<String, Long>()

    // Mutex for thread-safe operations
    private val mutex = Mutex()

    // Cooldown period to prevent rapid duplicate launches (milliseconds)
    private const val LAUNCH_COOLDOWN_MS = 2000L

    /**
     * Check if we can launch an activity
     * Returns true if launch is allowed, false if blocked due to cooldown
     */
    suspend fun canLaunchActivity(activityName: String): Boolean = mutex.withLock {
        val currentTime = System.currentTimeMillis()
        val lastLaunchTime = activeActivities[activityName]

        return if (lastLaunchTime == null || (currentTime - lastLaunchTime) > LAUNCH_COOLDOWN_MS) {
            activeActivities[activityName] = currentTime
            Log.d(TAG, "✓ Allowing launch of $activityName")
            true
        } else {
            val remainingCooldown = LAUNCH_COOLDOWN_MS - (currentTime - lastLaunchTime)
            Log.w(TAG, "✗ Blocking duplicate launch of $activityName (cooldown: ${remainingCooldown}ms)")
            false
        }
    }

    /**
     * Mark an activity as closed
     */
    suspend fun onActivityClosed(activityName: String) = mutex.withLock {
        activeActivities.remove(activityName)
        Log.d(TAG, "Activity closed: $activityName")
    }

    /**
     * Check if an activity is currently active
     */
    fun isActivityActive(activityName: String): Boolean {
        return activeActivities.containsKey(activityName)
    }

    /**
     * Clear all activity tracking (for reset scenarios)
     */
    suspend fun clearAll() = mutex.withLock {
        activeActivities.clear()
        Log.d(TAG, "All activity tracking cleared")
    }
}