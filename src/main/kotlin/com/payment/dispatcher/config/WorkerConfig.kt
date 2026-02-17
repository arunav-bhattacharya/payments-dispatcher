package com.payment.dispatcher.config

import com.payment.dispatcher.framework.activity.DispatcherActivitiesImpl
import com.payment.dispatcher.framework.activity.SchedulableContextActivities
import com.payment.dispatcher.framework.workflow.DispatcherWorkflowImpl
import com.payment.dispatcher.payment.exec.PaymentExecActivitiesImpl
import com.payment.dispatcher.payment.exec.PaymentExecWorkflowImpl
import com.payment.dispatcher.payment.init.PaymentInitActivitiesImpl
import com.payment.dispatcher.payment.init.PaymentInitWorkflowImpl
import io.quarkus.runtime.StartupEvent
import io.temporal.client.WorkflowClient
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject

/**
 * Manually creates and starts Temporal workers on application startup.
 *
 * Replaces the Quarkiverse Temporal extension's auto-discovery of
 * @TemporalWorkflow / @TemporalActivity annotations. Each worker is
 * configured with its task queue, concurrency limits, and registered
 * workflow/activity implementations.
 *
 * Three workers:
 * - **dispatch-worker** — runs the DispatcherWorkflow (dispatch cycle)
 * - **payment-init-worker** — runs Phase A (init workflow + context activities)
 * - **payment-exec-worker** — runs Phase B (exec workflow + exec activities)
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class WorkerConfig {

    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var config: AppConfig

    // Activity impls are CDI beans — inject them so they have @Inject fields populated
    @Inject
    lateinit var dispatcherActivities: DispatcherActivitiesImpl

    @Inject
    lateinit var paymentInitActivities: PaymentInitActivitiesImpl

    @Inject
    lateinit var paymentContextActivities: SchedulableContextActivities

    @Inject
    lateinit var paymentExecActivities: PaymentExecActivitiesImpl

    fun onStart(@Observes event: StartupEvent) {
        val factory = WorkerFactory.newInstance(workflowClient)

        createDispatchWorker(factory)
        createPaymentInitWorker(factory)
        createPaymentExecWorker(factory)

        factory.start()
        logger.info { "All Temporal workers started" }
    }

    private fun createDispatchWorker(factory: WorkerFactory) {
        val workerConfig = config.workers().dispatch()
        val worker = factory.newWorker(
            config.taskQueues().dispatcher(),
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(workerConfig.maxConcurrentWorkflows())
                .setMaxConcurrentActivityExecutionSize(workerConfig.maxConcurrentActivities())
                .build()
        )

        worker.registerWorkflowImplementationTypes(DispatcherWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(dispatcherActivities)

        logger.info { "Dispatch worker created: taskQueue=${config.taskQueues().dispatcher()}, maxWF=${workerConfig.maxConcurrentWorkflows()}, maxAct=${workerConfig.maxConcurrentActivities()}" }
    }

    private fun createPaymentInitWorker(factory: WorkerFactory) {
        val workerConfig = config.workers().paymentInit()
        val worker = factory.newWorker(
            config.taskQueues().paymentInit(),
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(workerConfig.maxConcurrentWorkflows())
                .setMaxConcurrentActivityExecutionSize(workerConfig.maxConcurrentActivities())
                .build()
        )

        worker.registerWorkflowImplementationTypes(PaymentInitWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(paymentInitActivities, paymentContextActivities)

        logger.info { "Payment init worker created: taskQueue=${config.taskQueues().paymentInit()}, maxWF=${workerConfig.maxConcurrentWorkflows()}, maxAct=${workerConfig.maxConcurrentActivities()}" }
    }

    private fun createPaymentExecWorker(factory: WorkerFactory) {
        val workerConfig = config.workers().paymentExec()
        val worker = factory.newWorker(
            config.taskQueues().paymentExec(),
            WorkerOptions.newBuilder()
                .setMaxConcurrentWorkflowTaskExecutionSize(workerConfig.maxConcurrentWorkflows())
                .setMaxConcurrentActivityExecutionSize(workerConfig.maxConcurrentActivities())
                .build()
        )

        worker.registerWorkflowImplementationTypes(PaymentExecWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(paymentExecActivities)

        logger.info { "Payment exec worker created: taskQueue=${config.taskQueues().paymentExec()}, maxWF=${workerConfig.maxConcurrentWorkflows()}, maxAct=${workerConfig.maxConcurrentActivities()}" }
    }
}
