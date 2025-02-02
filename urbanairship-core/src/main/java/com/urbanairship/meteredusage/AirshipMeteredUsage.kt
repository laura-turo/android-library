/* Copyright Airship and Contributors */

package com.urbanairship.meteredusage

import android.content.Context
import androidx.annotation.RestrictTo
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import com.urbanairship.AirshipComponent
import com.urbanairship.PreferenceDataStore
import com.urbanairship.PrivacyManager
import com.urbanairship.UALog
import com.urbanairship.UAirship
import com.urbanairship.annotation.OpenForTesting
import com.urbanairship.config.AirshipRuntimeConfig
import com.urbanairship.http.RequestResult
import com.urbanairship.job.JobDispatcher
import com.urbanairship.job.JobInfo
import com.urbanairship.job.JobResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking

/**
 * Airship Metered Usage tracker.
 *
 * @hide
 */
@OpenForTesting
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class AirshipMeteredUsage @JvmOverloads internal constructor(
    context: Context,
    dataStore: PreferenceDataStore,
    config: AirshipRuntimeConfig,
    private val privacyManager: PrivacyManager,
    private val store: EventsDao = EventsDatabase.persistent(context).eventsDao(),
    private val client: MeteredUsageApiClient = MeteredUsageApiClient(config),
    private val jobDispatcher: JobDispatcher = JobDispatcher.shared(context),
) : AirshipComponent(context, dataStore) {

    internal companion object {

        private const val WORK_ID = "MeteredUsage.upload"
        private const val RATE_LIMIT_ID = "MeteredUsage.rateLimit"
    }

    private val config: AtomicReference<Config> = AtomicReference(Config.default())

    internal fun setConfig(config: Config) {
        val old = this.config.getAndSet(config)
        if (old == config) {
            return
        }

        jobDispatcher.setRateLimit(RATE_LIMIT_ID, 1, config.intervalMs, TimeUnit.MILLISECONDS)

        if (!old.isEnabled && config.isEnabled) {
            scheduleUpload(delay = config.initialDelayMs)
        }
    }

    @VisibleForTesting
    internal fun scheduleUpload(
        delay: Long = 0L,
        conflictStrategy: Int = JobInfo.KEEP
    ) {
        if (!config.get().isEnabled) {
            return
        }

        jobDispatcher.dispatch(JobInfo.newBuilder()
            .setAirshipComponent(AirshipMeteredUsage::class.java)
            .setAction(WORK_ID)
            .setConflictStrategy(conflictStrategy)
            .setNetworkAccessRequired(true)
            .setMinDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        )
    }

    @WorkerThread
    public fun addEvent(event: MeteredUsageEventEntity) {
        if (!config.get().isEnabled) {
            return
        }

        val eventToStore = when (privacyManager.isEnabled(PrivacyManager.FEATURE_ANALYTICS)) {
            true -> event
            false -> event.withAnalyticsDisabled()
        }

        store.addEvent(eventToStore)
        scheduleUpload()
    }

    override fun onPerformJob(airship: UAirship, jobInfo: JobInfo): JobResult {
        if (!config.get().isEnabled) {
            UALog.v { "Config disabled, skipping upload." }
            return JobResult.SUCCESS
        }

        var events = store.getAllEvents()
        if (events.isEmpty()) {
            UALog.v { "No events, skipping upload." }
            return JobResult.SUCCESS
        }

        var channelId = airship.channel.id
        if (!privacyManager.isEnabled(PrivacyManager.FEATURE_ANALYTICS)) {
            channelId = null
            events = events.map { it.withAnalyticsDisabled() }
        }

        UALog.v { "Uploading events" }

        val result = runBlocking {
            try {
                client.uploadEvents(events, channelId)
            } catch (ex: Exception) {
                return@runBlocking RequestResult(ex)
            }
        }

        if (!result.isSuccessful) {
            UALog.v { "Uploading failed" }
            return JobResult.FAILURE
        }

        UALog.v { "Uploading success" }

        store.deleteAll(events.map { it.eventId }.toList())

        return JobResult.SUCCESS
    }
}
