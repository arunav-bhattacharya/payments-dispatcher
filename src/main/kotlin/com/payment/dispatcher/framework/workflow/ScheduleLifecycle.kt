package com.payment.dispatcher.framework.workflow

import com.payment.dispatcher.framework.activity.SchedulableContextActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Composable helper that abstracts the scheduling concern from init workflows.
 *
 * Init workflows compose this helper to save execution context and enqueue
 * items for dispatch — without knowing anything about the dispatch queue,
 * context persistence, or the framework internals.
 *
 * Usage:
 * ```
 * class PaymentInitWorkflowImpl : PaymentInitWorkflow {
 *     private val scheduleLifecycle = ScheduleLifecycle("PAYMENT")
 *
 *     override fun initializePayment(paymentId: String, requestJson: String): String {
 *         // ... pure business logic ...
 *         scheduleLifecycle.schedule(contextJson, scheduledExecTime)
 *         return "ENQUEUED"
 *     }
 * }
 * ```
 *
 * This is NOT a workflow or an abstract base class — it's a plain composable
 * object that holds an activity stub and provides a single scheduling method.
 *
 * @param itemType The item type discriminator (e.g., "PAYMENT", "INVOICE")
 */
class ScheduleLifecycle(private val itemType: String) {

    private val contextActivities: SchedulableContextActivities = Workflow.newActivityStub(
        SchedulableContextActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    /**
     * Saves execution context and enqueues the item for dispatch.
     *
     * Captures the current workflow ID automatically for tracing.
     *
     * @param contextJson       Full execution context serialized as JSON string
     * @param scheduledExecTime ISO-8601 timestamp for scheduled execution
     */
    fun schedule(contextJson: String, scheduledExecTime: String) {
        val workflowId = Workflow.getInfo().workflowId
        contextActivities.saveContextAndEnqueue(itemType, contextJson, scheduledExecTime, workflowId)
    }
}
