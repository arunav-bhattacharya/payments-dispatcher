package com.payment.dispatcher.framework.activity

import com.payment.dispatcher.framework.model.ClaimedBatch
import com.payment.dispatcher.framework.model.DispatchConfig
import com.payment.dispatcher.framework.model.DispatchResult
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Generic dispatcher activities — reusable across all item types.
 * The dispatcher workflow calls these in sequence each cycle.
 *
 * All activities use maxAttempts=1 (no auto-retry).
 * A failed dispatch cycle is picked up by the next scheduled cycle.
 */
@ActivityInterface
interface DispatcherActivities {

    /**
     * Reads dispatch configuration from Oracle.
     * Includes kill switch check (config.enabled).
     */
    @ActivityMethod
    fun readDispatchConfig(itemType: String): DispatchConfig

    /**
     * Recovers stale CLAIMED items that were never dispatched.
     * Checks Temporal to confirm exec workflow doesn't exist before resetting.
     * Returns the number of items recovered.
     */
    @ActivityMethod
    fun recoverStaleClaims(config: DispatchConfig): Int

    /**
     * Claims a batch of READY items using FOR UPDATE SKIP LOCKED.
     * Atomic, contention-free batch claim.
     */
    @ActivityMethod
    fun claimBatch(config: DispatchConfig): ClaimedBatch

    /**
     * Dispatches the entire batch by starting exec workflows.
     * Uses WorkflowOptions.setStartDelay() for jitter.
     * Single activity for the whole batch (not per-item fan-out).
     */
    @ActivityMethod
    fun dispatchBatch(batch: ClaimedBatch, config: DispatchConfig): List<DispatchResult>

    /**
     * Records dispatch results to the audit log.
     */
    @ActivityMethod
    fun recordResults(batchId: String, results: List<DispatchResult>, config: DispatchConfig)
}
