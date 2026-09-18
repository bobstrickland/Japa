package org.strickland.japa

/**
 * Callback interface used by CounterService to notify the bound Activity
 * of state changes without going through a broadcast.
 */
interface CounterCallback {
    fun onCountUpdated(
        currentBead: Int, currentRound: Int,
        totalBeads: Int, totalRounds: Int, isComplete: Boolean
    )
}
