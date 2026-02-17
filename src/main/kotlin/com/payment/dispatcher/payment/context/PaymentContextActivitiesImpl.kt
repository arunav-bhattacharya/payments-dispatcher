package com.payment.dispatcher.payment.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.framework.activity.SchedulableContextActivities
import com.payment.dispatcher.framework.context.ExposedContextService
import com.payment.dispatcher.framework.repository.DispatchQueueRepository
import com.payment.dispatcher.payment.model.PaymentExecContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant

/**
 * Payment-specific implementation of [SchedulableContextActivities].
 *
 * Handles saving the accumulated PaymentExecContext as a JSON CLOB
 * and enqueueing the payment for dispatch. Called by [ScheduleLifecycle]
 * at the end of Phase A (init workflow).
 *
 * This is the key composition seam: another domain (e.g., Invoice)
 * would create InvoiceContextActivitiesImpl implementing the same
 * [SchedulableContextActivities] interface with InvoiceExecContext.
 */
@ApplicationScoped
class PaymentContextActivitiesImpl : SchedulableContextActivities {

    @Inject
    lateinit var objectMapper: ObjectMapper

    @Inject
    lateinit var queueRepo: DispatchQueueRepository

    /** Lazy-initialized context service parameterized with PaymentExecContext */
    private val contextService by lazy {
        ExposedContextService<PaymentExecContext>(objectMapper)
    }

    companion object {
        private val log = Logger.getLogger(PaymentContextActivitiesImpl::class.java)
    }

    override fun saveContextAndEnqueue(
        itemType: String,
        contextJson: String,
        scheduledExecTime: String,
        initWorkflowId: String?
    ) {
        val context = objectMapper.readValue(contextJson, PaymentExecContext::class.java)

        // Save context FIRST — if enqueue fails, orphaned context is harmless
        contextService.save(context.paymentId, itemType, context)

        // Then enqueue for dispatch
        queueRepo.enqueue(
            itemId = context.paymentId,
            itemType = itemType,
            scheduledExecTime = Instant.parse(scheduledExecTime),
            initWorkflowId = initWorkflowId
        )

        log.infof("Payment %s: context saved and enqueued for execution at %s",
            context.paymentId, scheduledExecTime)
    }
}
