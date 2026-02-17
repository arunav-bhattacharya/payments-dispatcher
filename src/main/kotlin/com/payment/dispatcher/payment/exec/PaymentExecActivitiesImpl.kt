package com.payment.dispatcher.payment.exec

import com.payment.dispatcher.payment.model.PaymentExecContext
import com.payment.dispatcher.payment.model.PaymentStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped

/**
 * Phase B execution activity implementations.
 * These are TODO stubs — replace with actual payment execution logic.
 *
 * Payment status transitions during execution:
 *   SCHEDULED → ACCEPTED (after validation in post-schedule flow)
 *   ACCEPTED  → PROCESSING (after notifications sent to all parties)
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class PaymentExecActivitiesImpl : PaymentExecActivities {

    override fun executePayment(context: PaymentExecContext) {
        logger.info { "Executing payment ${context.paymentId}: ${context.amount} ${context.currency} from ${context.sourceAccount} to ${context.destinationAccount}" }

        // TODO: Validate payment in the post-schedule flow
        // - Re-confirm account state, funds availability at execution time
        // - Check for any holds or blocks placed since scheduling
        //
        // On successful validation, transition payment status:
        //   UPDATE PAYMENTS SET status = 'ACCEPTED' WHERE payment_id = ? AND status = 'SCHEDULED'
        logger.info { "Payment ${context.paymentId} validated and status transitioned to ${PaymentStatus.ACCEPTED}" }

        // TODO: Execute the actual payment against external payment rails
        // - Debit source account
        // - Credit destination account
        // - Settlement processing
        // - Record transaction in core ledger
        //
        // Use context.validationResult, context.enrichmentData,
        // context.appliedRules, context.fxRateSnapshot as needed — all Phase A work
        // is already done and available in the context.

        logger.info { "Payment ${context.paymentId} executed successfully" }
    }

    override fun postProcess(context: PaymentExecContext) {
        logger.debug { "Post-processing payment ${context.paymentId}" }

        // TODO: Post-processing tasks
        // - Update ledger entries
        // - Create reconciliation records
        // - Update payment status in core banking system
        // - Generate regulatory reports if needed
    }

    override fun sendNotifications(context: PaymentExecContext) {
        logger.debug { "Sending notifications for payment ${context.paymentId}" }

        // TODO: Send notifications
        // - Email to payer/payee
        // - SMS alerts if configured
        // - Webhook callbacks to integration partners
        // - Push notifications to mobile apps
        //
        // After all parties are notified, transition payment status:
        //   UPDATE PAYMENTS SET status = 'PROCESSING' WHERE payment_id = ? AND status = 'ACCEPTED'
        logger.info { "Payment ${context.paymentId}: all parties notified, status transitioned to ${PaymentStatus.PROCESSING}" }
    }
}
