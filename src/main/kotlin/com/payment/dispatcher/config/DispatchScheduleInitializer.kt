package com.payment.dispatcher.config

import com.payment.dispatcher.framework.schedule.DispatchScheduleSetup
import io.quarkus.runtime.StartupEvent
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject

/**
 * Creates the Temporal dispatch schedule on application startup.
 * Non-fatal if schedule already exists from a previous deployment.
 */
private val logger = KotlinLogging.logger {}

@ApplicationScoped
class DispatchScheduleInitializer {

    @Inject
    lateinit var scheduleSetup: DispatchScheduleSetup

    @Inject
    lateinit var config: AppConfig

    fun onStart(@Observes event: StartupEvent) {
        if (!config.autoCreateSchedule()) {
            logger.info { "Auto-create schedule disabled — skipping" }
            return
        }

        try {
            scheduleSetup.createOrUpdate(
                scheduleId = config.scheduleId(),
                taskQueue = config.taskQueues().dispatcher(),
                intervalSecs = config.dispatchIntervalSecs(),
                itemType = config.defaultItemType()
            )
            logger.info { "Dispatch schedule initialized: scheduleId=${config.scheduleId()}, interval=${config.dispatchIntervalSecs()}s, itemType=${config.defaultItemType()}" }
        } catch (e: Exception) {
            logger.warn { "Failed to initialize dispatch schedule: ${e.message} (non-fatal — may already exist)" }
        }
    }
}
