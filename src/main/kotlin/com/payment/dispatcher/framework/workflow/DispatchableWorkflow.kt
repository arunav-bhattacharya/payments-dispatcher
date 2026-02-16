package com.payment.dispatcher.framework.workflow

import com.payment.dispatcher.framework.activity.DispatcherActivities
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Abstract base class for workflows started by the dispatch framework.
 *
 * Implements the Template Method pattern: handles all dispatch lifecycle
 * concerns (marking COMPLETED, marking FAILED/DEAD_LETTER, context cleanup)
 * so that subclasses only implement pure business logic via [doExecute].
 *
 * Usage:
 * ```
 * @TemporalWorkflow(workers = ["payment-exec-worker"])
 * class PaymentExecWorkflowImpl : DispatchableWorkflow(), PaymentExecWorkflow {
 *     override fun execute(paymentId: String, contextJson: String) =
 *         executeWithLifecycle(paymentId, itemType, contextJson)
 *
 *     override fun doExecute(paymentId: String, contextJson: String) {
 *         // pure business logic only
 *     }
 * }
 * ```
 *
 * This is NOT a @WorkflowInterface — each domain defines its own interface.
 * This class only provides the lifecycle template.
 */
abstract class DispatchableWorkflow {

    /** The item type used for dispatch config lookup (e.g., "PAYMENT", "INVOICE"). */
    protected abstract val itemType: String

    private val dispatcherActivities: DispatcherActivities = Workflow.newActivityStub(
        DispatcherActivities::class.java,
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
     * Template method that wraps business logic with dispatch lifecycle management.
     * Subclasses call this from their @WorkflowMethod implementation.
     *
     * On success: marks COMPLETED + deletes context CLOB via DispatcherActivities.
     * On failure: marks FAILED/DEAD_LETTER via DispatcherActivities, then re-throws.
     */
    protected fun executeWithLifecycle(paymentId: String, contextJson: String) {
        try {
            doExecute(paymentId, contextJson)

            // Success: mark COMPLETED + delete context CLOB
            dispatcherActivities.completeItem(paymentId)

        } catch (e: Exception) {
            // Failure: mark FAILED or DEAD_LETTER
            try {
                dispatcherActivities.failItem(paymentId, itemType, e.message)
            } catch (ignored: Exception) {
                // Best-effort failure marking — stale recovery is the safety net
            }

            // Re-throw so Temporal records the workflow failure
            throw e
        }
    }

    /**
     * Subclasses implement this with pure business logic.
     * No dispatch lifecycle concerns — just execute, postProcess, notify, etc.
     *
     * @param paymentId   The item ID
     * @param contextJson Pre-loaded execution context as JSON string
     */
    protected abstract fun doExecute(paymentId: String, contextJson: String)
}
