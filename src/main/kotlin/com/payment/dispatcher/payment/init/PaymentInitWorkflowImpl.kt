package com.payment.dispatcher.payment.init

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.payment.dispatcher.framework.workflow.ScheduleLifecycle
import io.temporal.activity.ActivityOptions
import io.temporal.common.RetryOptions
import io.temporal.workflow.Workflow
import java.time.Duration

/**
 * Phase A implementation: runs all initial processing activities,
 * persists the payment as SCHEDULED, builds accumulated context,
 * and schedules for dispatch via [ScheduleLifecycle].
 *
 * The scheduling concern (context persistence + enqueueing) is
 * abstracted into [ScheduleLifecycle] — this workflow only contains
 * pure business logic and a single `schedule()` call at the end.
 *
 * After scheduling, the workflow returns "ENQUEUED" and COMPLETES.
 * No Workflow.sleep() — the payment waits in Oracle until dispatch time.
 */
class PaymentInitWorkflowImpl : PaymentInitWorkflow {

    companion object {
        private val objectMapper = ObjectMapper().registerModule(kotlinModule())
    }

    private val initActivities = Workflow.newActivityStub(
        PaymentInitActivities::class.java,
        ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryOptions(
                RetryOptions.newBuilder()
                    .setMaximumAttempts(3)
                    .build()
            )
            .build()
    )

    private val scheduleLifecycle = ScheduleLifecycle("PAYMENT")

    override fun initializePayment(paymentId: String, requestJson: String): String {
        // ═══ Phase A: Validate and enrich ═══
        val validationResult = initActivities.validatePayment(paymentId, requestJson)
        val enrichmentData = initActivities.enrichPayment(paymentId, requestJson)
        val appliedRules = initActivities.applyRules(paymentId, requestJson)

        // ═══ Persist payment with SCHEDULED status ═══
        initActivities.persistScheduledPayment(paymentId, requestJson)

        // ═══ Build accumulated context ═══
        val context = initActivities.buildContext(
            paymentId = paymentId,
            requestJson = requestJson,
            validationResultJson = validationResult,
            enrichmentDataJson = enrichmentData,
            appliedRulesJson = appliedRules
        )

        // ═══ Determine execution time ═══
        val scheduledExecTime = initActivities.determineExecTime(paymentId, requestJson)

        // ═══ Schedule for dispatch (context save + enqueue) ═══
        scheduleLifecycle.schedule(objectMapper.writeValueAsString(context), scheduledExecTime)

        // Workflow COMPLETES — no more sleeping!
        return "ENQUEUED"
    }
}
