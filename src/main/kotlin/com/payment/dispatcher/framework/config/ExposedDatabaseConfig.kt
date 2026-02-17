package com.payment.dispatcher.framework.config

import io.agroal.api.AgroalDataSource
import jakarta.annotation.PostConstruct
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jetbrains.exposed.sql.Database

/**
 * Initializes Kotlin Exposed with the Quarkus-managed Agroal DataSource.
 * Exposed uses this connection for all `transaction { }` blocks.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class ExposedDatabaseConfig {

    @Inject
    lateinit var dataSource: AgroalDataSource

    @PostConstruct
    fun init() {
        Database.connect(dataSource)
        logger.info { "Kotlin Exposed initialized with Quarkus Agroal DataSource" }
    }
}
