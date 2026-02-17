package com.payment.dispatcher.payment.exec

import com.payment.dispatcher.payment.model.PaymentExecContext
import com.payment.dispatcher.payment.model.PaymentStatus
import jakarta.enterprise.context.ApplicationScoped
import org.jboss.logging.Logger

/**
 * Phase B execution activity implementations.
 * These are TODO stubs — replace with actual payment execution logic.
 *
 * Payment status transitions during execution:
 *   SCHEDULED → ACCEPTED (after validation in post-schedule flow)
 *   ACCEPTED  → PROCESSING (after notifications sent to all parties)
 */
@ApplicationScoped
class PaymentExecActivitiesImpl : PaymentExecActivities {

    companion object {
        private val log = Logger.getLogger(PaymentExecActivitiesImpl::class.java)
    }

    override fun executePayment(context: PaymentExecContext) {
        log.infof("Executing payment %s: %s %s from %s to %s",
            context.paymentId,
            context.amount,
            context.currency,
            context.sourceAccount,
            context.destinationAccount)

        // TODO: Validate payment in the post-schedule flow
        // - Re-confirm account state, funds availability at execution time
        // - Check for any holds or blocks placed since scheduling
        //
        // On successful validation, transition payment status:
        //   UPDATE PAYMENTS SET status = 'ACCEPTED' WHERE payment_id = ? AND status = 'SCHEDULED'
        log.infof("Payment %s validated and status transitioned to %s",
            context.paymentId, PaymentStatus.ACCEPTED)

        // TODO: Execute the actual payment against external payment rails
        // - Debit source account
        // - Credit destination account
        // - Settlement processing
        // - Record transaction in core ledger
        //
        // Use context.validationResult, context.enrichmentData,
        // context.appliedRules, context.fxRateSnapshot as needed — all Phase A work
        // is already done and available in the context.

        log.infof("Payment %s executed successfully", context.paymentId)
    }

    override fun postProcess(context: PaymentExecContext) {
        log.debugf("Post-processing payment %s", context.paymentId)

        // TODO: Post-processing tasks
        // - Update ledger entries
        // - Create reconciliation records
        // - Update payment status in core banking system
        // - Generate regulatory reports if needed
    }

    override fun sendNotifications(context: PaymentExecContext) {
        log.debugf("Sending notifications for payment %s", context.paymentId)

        // TODO: Send notifications
        // - Email to payer/payee
        // - SMS alerts if configured
        // - Webhook callbacks to integration partners
        // - Push notifications to mobile apps
        //
        // After all parties are notified, transition payment status:
        //   UPDATE PAYMENTS SET status = 'PROCESSING' WHERE payment_id = ? AND status = 'ACCEPTED'
        log.infof("Payment %s: all parties notified, status transitioned to %s",
            context.paymentId, PaymentStatus.PROCESSING)
    }
}
