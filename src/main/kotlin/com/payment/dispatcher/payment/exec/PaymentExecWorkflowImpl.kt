package com.payment.dispatcher.payment.exec

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.payment.dispatcher.payment.model.PaymentExecContext
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase B implementation: pure payment business logic.
 *
 * This workflow has zero awareness of the dispatch framework.
 * It receives a pre-loaded contextJson parameter, deserializes it,
 * and executes the payment business logic — nothing more.
 *
 * The dispatch framework (DispatcherActivitiesImpl) starts this workflow
 * and manages queue status transitions externally. Stale recovery
 * handles any orphaned CLAIMED items if the dispatcher crashes
 * between claiming and starting the workflow.
 */
class PaymentExecWorkflowImpl : PaymentExecWorkflow {

    companion object {
        private val objectMapper = ObjectMapper().registerModule(kotlinModule())
    }

    private val execActivities = Workflow.newActivityStub(
        PaymentExecActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(2))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    override fun execute(paymentId: String, contextJson: String) {
        val context = objectMapper.readValue(contextJson, PaymentExecContext::class.java)

        // Execute the actual payment (debit, credit, settlement)
        // Transitions payment status: SCHEDULED → ACCEPTED
        execActivities.executePayment(context)

        // Post-processing (ledger updates, reconciliation)
        execActivities.postProcess(context)

        // Send notifications (email, SMS, webhooks)
        // Transitions payment status: ACCEPTED → PROCESSING
        execActivities.sendNotifications(context)
    }
}
