package com.payment.dispatcher.framework.context

import com.fasterxml.jackson.databind.ObjectMapper
import com.payment.dispatcher.framework.repository.tables.ExecContextTable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Instant

/**
 * Generic Exposed-based context persistence using Oracle CLOB (via Exposed text column).
 * Uses Jackson for JSON serialization/deserialization.
 *
 * Not @ApplicationScoped directly — domain-specific subclasses provide the concrete type.
 * Example: PaymentContextService extends ExposedContextService<PaymentExecContext>.
 *
 * Uses insert-first strategy: attempts INSERT, falls back to UPDATE on duplicate key.
 * This avoids Oracle MERGE overhead from Exposed's upsert().
 */
private val logger = KotlinLogging.logger {}

open class ExposedContextService<T>(
    private val objectMapper: ObjectMapper
) : ExecutionContextService<T> {

    companion object {
        // Oracle unique constraint violation: ORA-00001
        private const val ORACLE_UNIQUE_VIOLATION = 1
    }

    /**
     * Saves context as JSON CLOB using insert-first strategy.
     * Attempts INSERT; on duplicate key (ORA-00001), falls back to UPDATE.
     * Safe on Temporal activity retries — duplicate inserts are handled gracefully.
     */
    override fun save(itemId: String, itemType: String, context: T) {
        val json = objectMapper.writeValueAsString(context)
        val now = Instant.now()

        transaction {
            try {
                ExecContextTable.insert {
                    it[paymentId] = itemId
                    it[ExecContextTable.itemType] = itemType
                    it[contextJson] = json
                    it[contextVersion] = 1
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            } catch (e: ExposedSQLException) {
                val isUniqueViolation = e.sqlState == "23000" ||
                    (e.cause as? java.sql.SQLException)?.errorCode == ORACLE_UNIQUE_VIOLATION
                if (isUniqueViolation) {
                    logger.debug { "Context already exists for itemId=$itemId — updating" }
                    ExecContextTable.update({ ExecContextTable.paymentId eq itemId }) {
                        it[contextJson] = json
                        it[contextVersion] = 1
                        it[updatedAt] = now
                    }
                } else {
                    throw e
                }
            }
        }

        logger.debug { "Saved context for itemId=$itemId (type=$itemType, size=${json.length} bytes)" }
    }

    /**
     * Loads and deserializes context from Oracle CLOB.
     * @throws IllegalStateException if context not found
     */
    override fun load(itemId: String, clazz: Class<T>): T {
        return transaction {
            val row = ExecContextTable.selectAll()
                .where { ExecContextTable.paymentId eq itemId }
                .firstOrNull()
                ?: throw IllegalStateException("Execution context not found for itemId=$itemId")

            val json = row[ExecContextTable.contextJson]
            objectMapper.readValue(json, clazz)
        }
    }

    /**
     * Deletes context after successful execution (cleanup).
     */
    override fun delete(itemId: String) {
        transaction {
            ExecContextTable.deleteWhere { paymentId eq itemId }
        }
        logger.debug { "Deleted context for itemId=$itemId" }
    }

    /**
     * Checks if context exists for diagnostics and guards.
     */
    override fun exists(itemId: String): Boolean {
        return transaction {
            ExecContextTable.selectAll()
                .where { ExecContextTable.paymentId eq itemId }
                .count() > 0
        }
    }
}
