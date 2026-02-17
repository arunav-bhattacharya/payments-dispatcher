package com.payment.dispatcher.config

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton

/**
 * CDI producer for Temporal client infrastructure.
 *
 * Creates [WorkflowServiceStubs] and [WorkflowClient] as singleton CDI beans,
 * configured from application.yaml via [AppConfig].
 *
 * Replaces the Quarkiverse Temporal extension's auto-configuration.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class TemporalConfig {

    @Inject
    lateinit var config: AppConfig

    @Produces
    @Singleton
    fun workflowServiceStubs(): WorkflowServiceStubs {
        val target = config.temporal().target()
        logger.info { "Connecting to Temporal server at $target" }

        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(target)
                .build()
        )
    }

    @Produces
    @Singleton
    fun workflowClient(serviceStubs: WorkflowServiceStubs): WorkflowClient {
        val namespace = config.temporal().namespace()
        logger.info { "Creating Temporal WorkflowClient for namespace=$namespace" }

        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build()
        )
    }
}
