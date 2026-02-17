package com.payment.dispatcher.framework.workflow

import com.payment.dispatcher.framework.activity.DispatcherActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Generic dispatcher workflow implementation.
 * Runs as a short-lived workflow triggered by a Temporal Schedule every N seconds.
 *
 * Sequence per cycle:
 * 1. Read config (includes kill switch check)
 * 2. Recover stale claims (self-healing)
 * 3. Claim batch (FOR UPDATE SKIP LOCKED)
 * 4. Dispatch batch (start exec workflows with startDelay jitter)
 * 5. Record results (audit log)
 *
 * Activity retry policy: maxAttempts=1.
 * A failed cycle is simply picked up by the next scheduled invocation.
 */
class DispatcherWorkflowImpl : DispatcherWorkflow {

    private val activities = Workflow.newActivityStub(
        DispatcherActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(1) // No auto-retry — next schedule fires handle failures
                    .build()
            )
            .build()
    )

    override fun dispatch(itemType: String) {
        // 1. Read config — includes kill switch
        val config = activities.readDispatchConfig(itemType)
        if (!config.enabled) return

        // 2. Recover stale claims from previous failed cycles
        activities.recoverStaleClaims(config)

        // 3. Claim a batch of READY items (atomic, contention-free)
        val batch = activities.claimBatch(config)
        if (batch.items.isEmpty()) return

        // 4. Dispatch entire batch in a single activity (startDelay for jitter)
        val results = activities.dispatchBatch(batch, config)

        // 5. Record audit summary
        activities.recordResults(batch.batchId, results, config)
    }
}
