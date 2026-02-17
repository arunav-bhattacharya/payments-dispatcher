package com.payment.dispatcher.framework.repository

import com.payment.dispatcher.framework.model.ClaimedItem
import com.payment.dispatcher.framework.model.QueueStatus
import com.payment.dispatcher.framework.repository.tables.ExecContextTable
import com.payment.dispatcher.framework.repository.tables.ExecQueueTable
import com.payment.dispatcher.framework.repository.tables.ExecRateConfigTable
import io.agroal.api.AgroalDataSource
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Timestamp
import java.time.Instant

/**
 * Core dispatch queue operations using Kotlin Exposed DSL.
 * The claimBatch method uses raw JDBC for Oracle-native FOR UPDATE SKIP LOCKED,
 * since Exposed DSL only provides PostgreSQL-specific ForUpdateOption.
 * All other operations use Exposed DSL.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class DispatchQueueRepository {

    @Inject
    lateinit var dataSource: AgroalDataSource

    // ═══════════════════════════════════════════════════════════════════
    // Enqueue — Called by init workflows (Phase A)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Inserts a new item into the dispatch queue with status READY.
     * Called at the end of Phase A (init workflow) after context is saved.
     */
    fun enqueue(
        itemId: String,
        itemType: String,
        scheduledExecTime: Instant,
        initWorkflowId: String?
    ) {
        transaction {
            ExecQueueTable.insert {
                it[paymentId] = itemId
                it[ExecQueueTable.itemType] = itemType
                it[queueStatus] = QueueStatus.READY.name
                it[ExecQueueTable.scheduledExecTime] = scheduledExecTime
                it[ExecQueueTable.initWorkflowId] = initWorkflowId
                it[retryCount] = 0
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Batch Claim — Called by dispatcher activities
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Claims a batch of READY items atomically using FOR UPDATE SKIP LOCKED.
     *
     * Three-step process within a single JDBC transaction:
     * 1. SELECT FOR UPDATE SKIP LOCKED — lock candidate rows (raw SQL for Oracle)
     * 2. UPDATE locked rows — set status to CLAIMED (raw SQL, same connection)
     * 3. Fetch full details via Exposed DSL — return claimed items with config info
     *    and pre-loaded context JSON (joined with PAYMENT_EXEC_CONTEXT)
     *
     * Steps 1-2 use raw JDBC because Exposed DSL only has PostgreSQL ForUpdateOption.
     * Step 3 uses Exposed DSL since no locking is needed.
     *
     * @return List of claimed items ready for dispatch (may be empty)
     */
    fun claimBatch(
        itemType: String,
        batchSize: Int,
        cutoffTime: Instant,
        maxRetries: Int,
        batchId: String
    ): List<ClaimedItem> {
        // Steps 1-2: Raw JDBC for Oracle FOR UPDATE SKIP LOCKED
        val claimedIds = claimBatchRaw(itemType, batchSize, cutoffTime, maxRetries, batchId)

        if (claimedIds.isEmpty()) {
            return emptyList()
        }

        // Step 3: Fetch full details + pre-load context JSON via Exposed DSL
        return transaction {
            val configRow = ExecRateConfigTable.selectAll()
                .where { ExecRateConfigTable.itemType eq itemType }
                .firstOrNull()

            val execWorkflowType = configRow?.get(ExecRateConfigTable.execWorkflowType)
                ?: error("No EXEC_RATE_CONFIG found for itemType=$itemType")
            val execTaskQueue = configRow[ExecRateConfigTable.execTaskQueue]

            (ExecQueueTable leftJoin ExecContextTable)
                .selectAll()
                .where { ExecQueueTable.dispatchBatchId eq batchId }
                .orderBy(ExecQueueTable.scheduledExecTime to SortOrder.ASC)
                .map { row ->
                    ClaimedItem(
                        itemId = row[ExecQueueTable.paymentId],
                        itemType = row[ExecQueueTable.itemType],
                        scheduledExecTime = row[ExecQueueTable.scheduledExecTime].toString(),
                        initWorkflowId = row[ExecQueueTable.initWorkflowId],
                        execWorkflowType = execWorkflowType,
                        execTaskQueue = execTaskQueue,
                        contextJson = row[ExecContextTable.contextJson]
                    )
                }
        }
    }

    /**
     * Raw JDBC implementation of SELECT FOR UPDATE SKIP LOCKED + UPDATE.
     * Oracle supports FOR UPDATE SKIP LOCKED natively.
     * Uses a single JDBC connection/transaction to ensure the locks are held
     * while the UPDATE executes.
     *
     * @return List of payment IDs that were successfully claimed
     */
    private fun claimBatchRaw(
        itemType: String,
        batchSize: Int,
        cutoffTime: Instant,
        maxRetries: Int,
        batchId: String
    ): List<String> {
        val candidateIds = mutableListOf<String>()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                // Step 1: SELECT FOR UPDATE SKIP LOCKED (Oracle-native syntax)
                val selectSql = """
                    SELECT payment_id FROM PAYMENT_EXEC_QUEUE
                    WHERE item_type = ?
                      AND queue_status = ?
                      AND scheduled_exec_time <= ?
                      AND retry_count < ?
                    ORDER BY scheduled_exec_time ASC
                    FETCH FIRST ? ROWS ONLY
                    FOR UPDATE SKIP LOCKED
                """.trimIndent()

                conn.prepareStatement(selectSql).use { ps ->
                    ps.setString(1, itemType)
                    ps.setString(2, QueueStatus.READY.name)
                    ps.setTimestamp(3, Timestamp.from(cutoffTime))
                    ps.setInt(4, maxRetries)
                    ps.setInt(5, batchSize)

                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            candidateIds.add(rs.getString("payment_id"))
                        }
                    }
                }

                if (candidateIds.isEmpty()) {
                    conn.commit()
                    return emptyList()
                }

                // Step 2: UPDATE locked rows to CLAIMED
                val placeholders = candidateIds.joinToString(",") { "?" }
                val updateSql = """
                    UPDATE PAYMENT_EXEC_QUEUE
                    SET queue_status = ?,
                        claimed_at = ?,
                        dispatch_batch_id = ?,
                        updated_at = ?
                    WHERE payment_id IN ($placeholders)
                """.trimIndent()

                val now = Timestamp.from(Instant.now())
                conn.prepareStatement(updateSql).use { ps ->
                    ps.setString(1, QueueStatus.CLAIMED.name)
                    ps.setTimestamp(2, now)
                    ps.setString(3, batchId)
                    ps.setTimestamp(4, now)
                    candidateIds.forEachIndexed { index, id ->
                        ps.setString(5 + index, id)
                    }
                    ps.executeUpdate()
                }

                conn.commit()
                logger.debug { "Claimed ${candidateIds.size} items for batchId=$batchId (itemType=$itemType)" }
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }

        return candidateIds
    }

    // ═══════════════════════════════════════════════════════════════════
    // Status Transitions — Called by dispatcher and exec workflows
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Transitions CLAIMED → DISPATCHED after exec workflow is started.
     */
    fun markDispatched(itemId: String, execWorkflowId: String) {
        transaction {
            val updated = ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                        (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name)
            }) {
                it[queueStatus] = QueueStatus.DISPATCHED.name
                it[dispatchedAt] = Instant.now()
                it[ExecQueueTable.execWorkflowId] = execWorkflowId
                it[updatedAt] = Instant.now()
            }

            if (updated == 0) {
                logger.warn { "markDispatched: no CLAIMED row found for itemId=$itemId (may already be DISPATCHED)" }
            }
        }
    }

    /**
     * Transitions DISPATCHED → COMPLETED on successful execution.
     */
    fun markCompleted(itemId: String) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                        (ExecQueueTable.queueStatus eq QueueStatus.DISPATCHED.name)
            }) {
                it[queueStatus] = QueueStatus.COMPLETED.name
                it[completedAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    /**
     * Marks an item as FAILED or DEAD_LETTER based on retry count.
     *
     * @return true if the item was moved to DEAD_LETTER
     */
    fun markFailed(itemId: String, error: String?, maxRetries: Int): Boolean {
        return transaction {
            val currentRow = ExecQueueTable.selectAll()
                .where { ExecQueueTable.paymentId eq itemId }
                .firstOrNull() ?: return@transaction false

            val currentRetryCount = currentRow[ExecQueueTable.retryCount]
            val newRetryCount = currentRetryCount + 1
            val isDeadLetter = newRetryCount >= maxRetries

            val newStatus = if (isDeadLetter) QueueStatus.DEAD_LETTER.name else QueueStatus.FAILED.name

            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                        (ExecQueueTable.queueStatus eq QueueStatus.DISPATCHED.name)
            }) {
                it[queueStatus] = newStatus
                it[retryCount] = newRetryCount
                it[lastError] = error?.take(4000)
                it[updatedAt] = Instant.now()
            }

            if (isDeadLetter) {
                logger.warn { "Item $itemId moved to DEAD_LETTER after $newRetryCount retries: ${error?.take(200)}" }
            }

            isDeadLetter
        }
    }

    /**
     * Resets a CLAIMED or FAILED item back to READY for retry in next dispatch cycle.
     */
    fun resetToReady(itemId: String, error: String?) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                        (ExecQueueTable.queueStatus inList listOf(
                            QueueStatus.CLAIMED.name,
                            QueueStatus.FAILED.name
                        ))
            }) {
                it[queueStatus] = QueueStatus.READY.name
                it[claimedAt] = null
                it[dispatchBatchId] = null
                it[execWorkflowId] = null
                it[retryCount] = ExecQueueTable.retryCount + 1
                it[lastError] = error?.take(4000)
                it[updatedAt] = Instant.now()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // Stale Claim Recovery
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Finds CLAIMED items older than the threshold that may be stuck.
     * The caller checks Temporal for each to confirm the exec workflow
     * is NOT running before resetting to READY.
     */
    fun findStaleClaims(
        itemType: String,
        thresholdMins: Int,
        maxRecovery: Int
    ): List<StaleClaim> {
        val staleThreshold = Instant.now().minusSeconds(thresholdMins.toLong() * 60)

        return transaction {
            ExecQueueTable.selectAll()
                .where {
                    (ExecQueueTable.itemType eq itemType) and
                            (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name) and
                            (ExecQueueTable.claimedAt lessEq staleThreshold)
                }
                .limit(maxRecovery)
                .map { row ->
                    StaleClaim(
                        itemId = row[ExecQueueTable.paymentId],
                        execWorkflowId = row[ExecQueueTable.execWorkflowId]
                    )
                }
        }
    }

    /**
     * Resets a stale CLAIMED item back to READY.
     * Should only be called after confirming the exec workflow does NOT exist in Temporal.
     */
    fun resetStaleToReady(itemId: String) {
        transaction {
            ExecQueueTable.update({
                (ExecQueueTable.paymentId eq itemId) and
                        (ExecQueueTable.queueStatus eq QueueStatus.CLAIMED.name)
            }) {
                it[queueStatus] = QueueStatus.READY.name
                it[claimedAt] = null
                it[dispatchBatchId] = null
                it[execWorkflowId] = null
                it[retryCount] = ExecQueueTable.retryCount + 1
                it[lastError] = "Stale claim recovery"
                it[updatedAt] = Instant.now()
            }
        }
    }
}

/**
 * Represents a stale claim found during recovery.
 */
data class StaleClaim(
    val itemId: String,
    val execWorkflowId: String?
)
