package com.payment.dispatcher.framework.activity

import com.payment.dispatcher.framework.metrics.DispatchMetrics
import com.payment.dispatcher.framework.model.ClaimedBatch
import com.payment.dispatcher.framework.model.ClaimedItem
import com.payment.dispatcher.framework.model.DispatchConfig
import com.payment.dispatcher.framework.model.DispatchResult
import com.payment.dispatcher.framework.repository.DispatchAuditRepository
import com.payment.dispatcher.framework.repository.DispatchConfigRepository
import com.payment.dispatcher.framework.repository.DispatchQueueRepository
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowExecutionAlreadyStarted
import io.temporal.client.WorkflowNotFoundException
import io.temporal.client.WorkflowOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Generic dispatcher activities implementation.
 * Handles batch claiming, dispatch with startDelay jitter, stale recovery, and audit logging.
 *
 * Reusable across all item types — parameterized by DispatchConfig.
 * Exec workflows are completely decoupled — the dispatcher only starts them and
 * hands off control to Temporal. Stale recovery handles orphaned CLAIMED items.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class DispatcherActivitiesImpl : DispatcherActivities {

    @Inject
    lateinit var configRepo: DispatchConfigRepository

    @Inject
    lateinit var queueRepo: DispatchQueueRepository

    @Inject
    lateinit var auditRepo: DispatchAuditRepository

    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var metrics: DispatchMetrics

    // ═══════════════════════════════════════════════════════════════════
    // Read Config
    // ═══════════════════════════════════════════════════════════════════

    override fun readDispatchConfig(itemType: String): DispatchConfig {
        return configRepo.findByItemType(itemType)
            ?: throw IllegalStateException(
                "No dispatch configuration found for itemType=$itemType. " +
                "Insert a row into EXEC_RATE_CONFIG."
            )
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stale Claim Recovery
    // Checks Temporal for each stale claim before resetting.
    // Capped at maxStaleRecoveryPerCycle to bound cycle duration.
    // ═══════════════════════════════════════════════════════════════════

    override fun recoverStaleClaims(config: DispatchConfig): Int {
        val staleClaims = queueRepo.findStaleClaims(
            itemType = config.itemType,
            thresholdMins = config.staleClaimThresholdMins,
            maxRecovery = config.maxStaleRecoveryPerCycle
        )

        if (staleClaims.isEmpty()) return 0

        var recoveredCount = 0
        for (claim in staleClaims) {
            // Only reset if the exec workflow does NOT exist in Temporal
            val workflowExists = claim.execWorkflowId?.let { checkWorkflowExists(it) } ?: false

            if (!workflowExists) {
                queueRepo.resetStaleToReady(claim.itemId)
                auditRepo.log(
                    batchId = "STALE-RECOVERY",
                    itemType = config.itemType,
                    paymentId = claim.itemId,
                    action = "STALE_RECOVERY",
                    statusBefore = "CLAIMED",
                    statusAfter = "READY",
                    detail = "Stale claim recovered; exec workflow not found in Temporal"
                )
                recoveredCount++
                metrics.recordStaleRecovery()
            } else {
                logger.debug { "Stale claim for ${claim.itemId} has running exec workflow ${claim.execWorkflowId} — skipping recovery" }
            }
        }

        if (recoveredCount > 0) {
            logger.info { "Recovered $recoveredCount stale claims for itemType=${config.itemType}" }
        }
        return recoveredCount
    }

    /**
     * Checks if a workflow exists in Temporal by attempting to describe it.
     * Returns true if the workflow exists (running or completed within retention).
     * Returns false if WorkflowNotFoundException is thrown (never existed or retention expired).
     * Returns true on any other error (conservative — avoid premature recovery).
     */
    private fun checkWorkflowExists(workflowId: String): Boolean {
        return try {
            workflowClient.newUntypedWorkflowStub(workflowId).describe()
            true
        } catch (e: WorkflowNotFoundException) {
            false
        } catch (e: Exception) {
            logger.warn { "Error checking workflow $workflowId existence: ${e.message} — assuming exists" }
            true // Conservative: assume exists to avoid premature recovery
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Claim
    // ═══════════════════════════════════════════════════════════════════

    override fun claimBatch(config: DispatchConfig): ClaimedBatch {
        val batchId = "BATCH-${UUID.randomUUID().toString().take(12)}"
        val cutoffTime = Instant.now().plus(config.preStartBufferMins.toLong(), ChronoUnit.MINUTES)

        val items = queueRepo.claimBatch(
            itemType = config.itemType,
            batchSize = config.batchSize,
            cutoffTime = cutoffTime,
            maxRetries = config.maxDispatchRetries,
            batchId = batchId
        )

        metrics.recordBatchClaimed(items.size)

        if (items.isNotEmpty()) {
            logger.info { "Claimed batch $batchId with ${items.size} items for itemType=${config.itemType}" }
        }

        return ClaimedBatch(batchId, config.itemType, items)
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Dispatch
    // Single activity dispatches entire batch (not per-item fan-out).
    // Uses setStartDelay() for jitter — no Workflow.sleep().
    // ═══════════════════════════════════════════════════════════════════

    override fun dispatchBatch(batch: ClaimedBatch, config: DispatchConfig): List<DispatchResult> {
        val jitterMaxMs = config.jitterWindowMs.toLong()

        return batch.items.map { item ->
            dispatchSingleItem(item, batch.batchId, jitterMaxMs)
        }
    }

    /**
     * Dispatches a single item by starting its exec workflow with startDelay jitter.
     * The pre-loaded contextJson is passed as a second argument to the exec workflow,
     * eliminating the need for the exec workflow to load context from Oracle.
     *
     * On success: marks DISPATCHED in Oracle.
     * On WorkflowExecutionAlreadyStarted: treats as success (idempotent).
     * On failure: resets to READY for retry in next cycle.
     */
    private fun  dispatchSingleItem(
        item: ClaimedItem,
        batchId: String,
        jitterMaxMs: Long
    ): DispatchResult {
        val itemId = item.itemId
        val itemType = item.itemType

        return try {
            // Calculate random jitter within window
            val jitterDelay = if (jitterMaxMs > 0) {
                Duration.ofMillis(ThreadLocalRandom.current().nextLong(0, jitterMaxMs))
            } else {
                Duration.ZERO
            }

            // Deterministic workflow ID: exec-{itemType}-{itemId}
            val workflowId = "exec-${itemType.lowercase()}-$itemId"

            val options = WorkflowOptions.newBuilder()
                .setWorkflowId(workflowId)
                .setTaskQueue(item.execTaskQueue)
                .setWorkflowExecutionTimeout(Duration.ofMinutes(10))
                .setStartDelay(jitterDelay) // KEY: startDelay instead of Workflow.sleep
                .build()

            // Untyped stub for genericity — works with any workflow type
            // Passes both itemId and pre-loaded contextJson as arguments
            val stub = workflowClient.newUntypedWorkflowStub(item.execWorkflowType, options)
            WorkflowClient.start(stub::start, itemId, item.contextJson)

            // Mark CLAIMED → DISPATCHED in Oracle
            queueRepo.markDispatched(itemId, workflowId)

            auditRepo.log(
                batchId = batchId,
                itemType = itemType,
                paymentId = itemId,
                action = "DISPATCHED",
                statusBefore = "CLAIMED",
                statusAfter = "DISPATCHED",
                detail = "workflowId=$workflowId, startDelay=${jitterDelay.toMillis()}ms"
            )

            metrics.recordDispatch()
            DispatchResult(itemId, success = true, workflowId = workflowId)

        } catch (e: WorkflowExecutionAlreadyStarted) {
            // Temporal-level dedup: workflow already running — treat as success
            val workflowId = "exec-${itemType.lowercase()}-$itemId"
            logger.warn { "Exec workflow already running for itemId=$itemId (workflowId=$workflowId)" }
            queueRepo.markDispatched(itemId, workflowId)
            DispatchResult(itemId, success = true, alreadyRunning = true, workflowId = workflowId)

        } catch (e: Exception) {
            // Dispatch failed — reset to READY for retry in next cycle
            logger.error(e) { "Failed to dispatch itemId=$itemId" }
            queueRepo.resetToReady(itemId, e.message)

            auditRepo.log(
                batchId = batchId,
                itemType = itemType,
                paymentId = itemId,
                action = "DISPATCH_FAILED",
                statusBefore = "CLAIMED",
                statusAfter = "READY",
                detail = "Error: ${e.message?.take(500)}"
            )

            metrics.recordDispatchFailure()
            DispatchResult(itemId, success = false, error = e.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Record Results
    // ═══════════════════════════════════════════════════════════════════

    override fun recordResults(batchId: String, results: List<DispatchResult>, config: DispatchConfig) {
        val successCount = results.count { it.success }
        val failCount = results.size - successCount

        auditRepo.logBatchSummary(
            batchId = batchId,
            itemType = config.itemType,
            totalDispatched = successCount,
            totalFailed = failCount,
            durationMs = 0 // Duration tracking is at workflow level via metrics
        )

        logger.info { "Batch $batchId complete: $successCount dispatched, $failCount failed (itemType=${config.itemType})" }
    }

}
