package com.payment.dispatcher.payment.init

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.payment.model.AppliedRule
import com.payment.dispatcher.payment.model.EnrichmentData
import com.payment.dispatcher.payment.model.PaymentExecContext
import com.payment.dispatcher.payment.model.PaymentRequest
import com.payment.dispatcher.payment.model.RoutingDetails
import com.payment.dispatcher.payment.model.ValidationResult
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Phase A activity implementations.
 * These are TODO stubs — replace with actual business logic that calls Oracle DB,
 * external services, rules engines, etc.
 */
@ApplicationScoped
class PaymentInitActivitiesImpl : PaymentInitActivities {

    @Inject
    lateinit var objectMapper: ObjectMapper

    companion object {
        private val log = Logger.getLogger(PaymentInitActivitiesImpl::class.java)
        private val MST_ZONE = ZoneId.of("America/Denver")
    }

    override fun validatePayment(paymentId: String, requestJson: String): String {
        log.debugf("Validating payment %s", paymentId)
        // TODO: Validate against Oracle DB — account exists, sufficient funds,
        //       sanctions screening, duplicate check, etc.
        val result = ValidationResult(
            valid = true,
            checks = listOf("ACCOUNT_EXISTS", "SUFFICIENT_FUNDS", "SANCTIONS_CLEAR"),
            warnings = emptyList()
        )
        return objectMapper.writeValueAsString(result)
    }

    override fun enrichPayment(paymentId: String, requestJson: String): String {
        log.debugf("Enriching payment %s", paymentId)
        // TODO: Enrich with account holder names, routing details,
        //       correspondent bank info, metadata from external services
        val result = EnrichmentData(
            sourceAccountName = "Source Account Holder",
            destinationAccountName = "Destination Account Holder",
            routingDetails = RoutingDetails(
                bankCode = "BANKUS33",
                branchCode = "001",
                intermediaryBank = null
            ),
            metadata = mapOf("channel" to "API", "priority" to "NORMAL")
        )
        return objectMapper.writeValueAsString(result)
    }

    override fun applyRules(paymentId: String, requestJson: String): String {
        log.debugf("Applying rules for payment %s", paymentId)
        // TODO: Run through business rules engine — compliance flags,
        //       approval requirements, routing rules, etc.
        val result = listOf(
            AppliedRule(
                ruleId = "RULE-001",
                ruleName = "Standard Processing",
                outcome = "APPROVED",
                details = "Payment meets standard processing criteria"
            )
        )
        return objectMapper.writeValueAsString(result)
    }

    override fun persistScheduledPayment(paymentId: String, requestJson: String): String {
        log.infof("Persisting payment %s with SCHEDULED status", paymentId)
        // TODO: Persist the payment record in the payments database with SCHEDULED status.
        // This is the first durable business state — the payment is now visible in the system.
        //
        // Example:
        //   INSERT INTO PAYMENTS (payment_id, status, amount, currency, ...)
        //   VALUES (?, 'SCHEDULED', ?, ?, ...)
        //
        // The payment status lifecycle is:
        //   SCHEDULED → ACCEPTED → PROCESSING

        log.infof("Payment %s persisted with status SCHEDULED", paymentId)
        return paymentId
    }

    override fun determineExecTime(paymentId: String, requestJson: String): String {
        val request = objectMapper.readValue(requestJson, PaymentRequest::class.java)

        // Use the scheduled exec time from the request if provided
        // Otherwise, default to 16:00 MST today (or tomorrow if past cutoff)
        return if (request.scheduledExecTime.isAfter(Instant.EPOCH.plusSeconds(1))) {
            request.scheduledExecTime.toString()
        } else {
            val now = ZonedDateTime.now(MST_ZONE)
            val cutoff = ZonedDateTime.of(LocalDate.now(), LocalTime.of(16, 0), MST_ZONE)
            val execTime = if (now.isBefore(cutoff)) cutoff else cutoff.plusDays(1)
            execTime.toInstant().toString()
        }
    }

    override fun buildContext(
        paymentId: String,
        requestJson: String,
        validationResultJson: String,
        enrichmentDataJson: String,
        appliedRulesJson: String
    ): PaymentExecContext {
        val request = objectMapper.readValue(requestJson, PaymentRequest::class.java)
        val validation = objectMapper.readValue(validationResultJson, ValidationResult::class.java)
        val enrichment = objectMapper.readValue(enrichmentDataJson, EnrichmentData::class.java)
        val rules: List<AppliedRule> = objectMapper.readValue(appliedRulesJson,
            objectMapper.typeFactory.constructCollectionType(List::class.java, AppliedRule::class.java))

        return PaymentExecContext(
            paymentId = paymentId,
            amount = request.amount,
            currency = request.currency,
            sourceAccount = request.sourceAccount,
            destinationAccount = request.destinationAccount,
            paymentType = request.paymentType,
            scheduledExecTime = request.scheduledExecTime.toString(),
            validationResult = validation,
            enrichmentData = enrichment,
            appliedRules = rules,
            fxRateSnapshot = null // TODO: Add FX rate lookup if cross-currency
        )
    }
}
