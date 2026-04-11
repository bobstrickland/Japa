package org.strickland.japa;

/**
 * Callback interface used by CounterService to notify the bound Activity
 * of state changes without going through a broadcast.
 */
public interface CounterCallback {
    void onCountUpdated(int currentBead, int currentRound,
                        int totalBeads, int totalRounds, boolean isComplete);
}
