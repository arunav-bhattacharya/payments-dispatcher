package com.payment.dispatcher.framework.schedule

import com.payment.dispatcher.framework.workflow.DispatcherWorkflow
import io.temporal.client.WorkflowClient
import io.temporal.client.schedules.Schedule
import io.temporal.client.schedules.ScheduleActionStartWorkflow
import io.temporal.client.schedules.ScheduleClient
import io.temporal.client.schedules.ScheduleIntervalSpec
import io.temporal.client.schedules.ScheduleOptions
import io.temporal.client.schedules.SchedulePolicy
import io.temporal.client.schedules.ScheduleSpec
import io.temporal.api.enums.v1.ScheduleOverlapPolicy
import io.temporal.serviceclient.WorkflowServiceStubs
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.time.Duration

/**
 * Creates and manages Temporal Schedules for dispatching.
 * Each item type gets its own schedule (e.g., "dispatch-payment-schedule").
 *
 * The schedule fires DispatcherWorkflow at a configurable interval
 * with OverlapPolicy.SKIP to prevent concurrent dispatchers.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class DispatchScheduleSetup {

    @Inject
    lateinit var workflowClient: WorkflowClient

    @Inject
    lateinit var workflowServiceStubs: WorkflowServiceStubs

    /**
     * Creates a Temporal Schedule for the dispatcher.
     * Idempotent: logs a warning if the schedule already exists.
     *
     * @param scheduleId   Unique schedule identifier (e.g., "dispatch-payment-schedule")
     * @param taskQueue    Task queue for the dispatcher worker
     * @param intervalSecs Dispatch interval in seconds (default: 5)
     * @param itemType     Item type passed as workflow argument (e.g., "PAYMENT")
     */
    fun createOrUpdate(
        scheduleId: String,
        taskQueue: String,
        intervalSecs: Int,
        itemType: String
    ) {
        try {
            val scheduleClient = ScheduleClient.newInstance(workflowServiceStubs)

            val schedule = Schedule.newBuilder()
                .setAction(
                    ScheduleActionStartWorkflow.newBuilder()
                        .setWorkflowType(DispatcherWorkflow::class.java)
                        .setArguments(itemType)
                        .setOptions(
                            io.temporal.client.WorkflowOptions.newBuilder()
                                .setTaskQueue(taskQueue)
                                .setWorkflowExecutionTimeout(Duration.ofMinutes(2))
                                .build()
                        )
                        .build()
                )
                .setSpec(
                    ScheduleSpec.newBuilder()
                        .setIntervals(
                            listOf(ScheduleIntervalSpec(Duration.ofSeconds(intervalSecs.toLong())))
                        )
                        .build()
                )
                .setPolicy(
                    SchedulePolicy.newBuilder()
                        .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                        .build()
                )
                .build()

            scheduleClient.createSchedule(
                scheduleId,
                schedule,
                ScheduleOptions.newBuilder().build()
            )

            logger.info { "Created dispatch schedule '$scheduleId' for itemType=$itemType (interval=${intervalSecs}s, taskQueue=$taskQueue)" }

        } catch (e: io.grpc.StatusRuntimeException) {
            // Schedule already exists — ALREADY_EXISTS status code
            if (e.status.code == io.grpc.Status.Code.ALREADY_EXISTS) {
                logger.info { "Dispatch schedule '$scheduleId' already exists — skipping creation" }
            } else {
                logger.warn { "Failed to create dispatch schedule '$scheduleId': ${e.message}" }
            }
        } catch (e: Exception) {
            logger.warn { "Failed to create dispatch schedule '$scheduleId': ${e.message}" }
            // Non-fatal: schedule may already exist from a previous deployment
        }
    }
}
