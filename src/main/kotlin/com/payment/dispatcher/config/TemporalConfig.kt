package com.payment.dispatcher.config

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Inject
import jakarta.inject.Singleton
import org.jboss.logging.Logger

/**
 * CDI producer for Temporal client infrastructure.
 *
 * Creates [WorkflowServiceStubs] and [WorkflowClient] as singleton CDI beans,
 * configured from application.yaml via [AppConfig].
 *
 * Replaces the Quarkiverse Temporal extension's auto-configuration.
 */
@ApplicationScoped
class TemporalConfig {

    @Inject
    lateinit var config: AppConfig

    companion object {
        private val log = Logger.getLogger(TemporalConfig::class.java)
    }

    @Produces
    @Singleton
    fun workflowServiceStubs(): WorkflowServiceStubs {
        val target = config.temporal().target()
        log.infof("Connecting to Temporal server at %s", target)

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
        log.infof("Creating Temporal WorkflowClient for namespace=%s", namespace)

        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build()
        )
    }
}
