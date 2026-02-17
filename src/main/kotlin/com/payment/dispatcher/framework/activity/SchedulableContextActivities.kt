package com.payment.dispatcher.framework.activity

import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod

/**
 * Generic framework-level activity interface for scheduling items into the dispatch queue.
 *
 * Domain-specific implementations (e.g., PaymentContextActivitiesImpl) implement this
 * interface to handle context persistence and enqueueing. The [ScheduleLifecycle] composable
 * helper uses this interface to abstract the scheduling concern from init workflows.
 *
 * The contextJson parameter carries the full execution context as a JSON string.
 * The item ID is embedded within the context — no separate itemId parameter needed.
 */
@ActivityInterface
interface SchedulableContextActivities {

    /**
     * Saves execution context to the persistence store and enqueues the item for dispatch.
     *
     * Order matters: saves context FIRST (idempotent via insert-first), then enqueues.
     * Since this is a single Temporal activity, failures are automatically retried —
     * the enqueue step cannot permanently fail.
     *
     * @param itemType          The item type discriminator (e.g., "PAYMENT", "INVOICE")
     * @param contextJson       Full execution context serialized as JSON string
     * @param scheduledExecTime ISO-8601 timestamp for scheduled execution
     * @param initWorkflowId    The init workflow ID for tracing (nullable)
     */
    @ActivityMethod
    fun saveContextAndEnqueue(
        itemType: String,
        contextJson: String,
        scheduledExecTime: String,
        initWorkflowId: String?
    )
}
