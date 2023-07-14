package com.urbanairship.experiment

import androidx.annotation.RestrictTo
import com.urbanairship.UALog
import com.urbanairship.audience.AudienceSelector
import com.urbanairship.json.JsonException
import com.urbanairship.json.JsonList
import com.urbanairship.json.JsonMap
import com.urbanairship.json.JsonSerializable
import com.urbanairship.json.JsonValue
import com.urbanairship.json.optionalField
import com.urbanairship.json.optionalFieldConverted
import com.urbanairship.json.requireField
import com.urbanairship.util.DateUtils

internal enum class ExperimentType(val jsonValue: String) {
    HOLDOUT_GROUP("holdout");

    companion object {
        fun from(value: String): ExperimentType? {
            return values().firstOrNull { it.jsonValue == value }
        }
    }
}

internal enum class ResolutionType(val jsonValue: String) {
    STATIC("static");

    companion object {
        fun from(value: String): ResolutionType? {
            return values().firstOrNull { it.jsonValue == value }
        }
    }
}

/** @hide */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class ExperimentResult(
    public val channelId: String,
    public val contactId: String,
    public val matchedExperimentId: String?,
    public val isMatching: Boolean,
    public val allEvaluatedExperimentsMetadata: List<JsonMap>
) : JsonSerializable {

    public companion object {
        private const val KEY_CHANNEL_ID = "channelId"
        private const val KEY_CONTACT_ID = "contactId"
        private const val KEY_MATCHED_EXPERIMENT_ID = "matchedExperimentId"
        private const val KEY_IS_MATCHING = "isMatching"
        private const val KEY_REPORTING_METADATA = "allEvaluatedExperimentsMetadata"

        public fun fromJson(json: JsonMap): ExperimentResult? {
            try {
                val reportingMetadata = json
                    .require(KEY_REPORTING_METADATA)
                    .optList()
                    .list
                    .map { it.optMap() }

                return ExperimentResult(
                    channelId = json.requireField(KEY_CHANNEL_ID),
                    contactId = json.requireField(KEY_CONTACT_ID),
                    matchedExperimentId = json.optionalField(KEY_MATCHED_EXPERIMENT_ID),
                    isMatching = json.requireField(KEY_IS_MATCHING),
                    allEvaluatedExperimentsMetadata = reportingMetadata
                )
            } catch (ex: JsonException) {
                UALog.e(ex) { "Failed to parse ExperimentResult" }
                return null
            }
        }
    }

    public fun evaluatedExperimentsDataAsJsonValue(): JsonValue {
        return allEvaluatedExperimentsMetadata
            .map { it.toJsonValue() }
            .let { JsonList(it) }
            .toJsonValue()
    }

    override fun toJsonValue(): JsonValue {
        return JsonMap
            .newBuilder()
            .put(KEY_CHANNEL_ID, channelId)
            .put(KEY_CONTACT_ID, contactId)
            .put(KEY_MATCHED_EXPERIMENT_ID, matchedExperimentId)
            .put(KEY_IS_MATCHING, isMatching)
            .put(KEY_REPORTING_METADATA, evaluatedExperimentsDataAsJsonValue())
            .build()
            .toJsonValue()
    }
}

/** @hide */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class MessageInfo(
    public val messageType: String,
    public val campaigns: JsonValue?
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageInfo

        if (messageType != other.messageType) return false

        return true
    }

    override fun hashCode(): Int {
        return messageType.hashCode()
    }
}

internal data class Experiment(
    val id: String,
    val type: ExperimentType,
    val resolutionType: ResolutionType,
    val created: Long,
    val lastUpdated: Long,
    val reportingMetadata: JsonMap,
    val audience: AudienceSelector,
    val exclusions: List<MessageCriteria>,
    val timeCriteria: TimeCriteria?
) {

    companion object {
        internal const val KEY_ID = "experiment_id"
        private const val KEY_CREATED = "created"
        private const val KEY_LAST_UPDATED = "last_updated"
        private const val KEY_EXPERIMENT_DEFINITION = "experiment_definition"

        private const val KEY_TYPE = "experiment_type"
        private const val KEY_REPORTING_METADATA = "reporting_metadata"
        private const val KEY_RESOLUTION_TYPE = "type"
        private const val KEY_AUDIENCE_SELECTOR = "audience_selector"
        private const val KEY_MESSAGE_EXCLUSION = "message_exclusions"
        private const val KEY_TIME_CRITERIA = "time_criteria"

        /**
         * Creates a `Experiment` object a [JsonMap].
         *
         * @param json A Remote Data payload containing configuration for an Experiment.
         *
         * @hide
         */
        internal fun fromJson(json: JsonMap): Experiment? {
            try {
                val definition = json.require(KEY_EXPERIMENT_DEFINITION).optMap()
                val type = definition.optionalFieldConverted(KEY_TYPE, ExperimentType::from)
                    ?: return null
                val resolutionType = definition.optionalFieldConverted(KEY_RESOLUTION_TYPE, ResolutionType::from)
                    ?: return null
                val audience = AudienceSelector.fromJson(definition.require(KEY_AUDIENCE_SELECTOR))

                val exclusions = definition
                    .opt(KEY_MESSAGE_EXCLUSION)
                    .optList()
                    .map { it.optMap() }
                    .mapNotNull(MessageCriteria::fromJson)

                return Experiment(
                    id = json.requireField(KEY_ID),
                    type = type,
                    resolutionType = resolutionType,
                    created = DateUtils.parseIso8601(json.requireField(KEY_CREATED)),
                    lastUpdated = DateUtils.parseIso8601(json.requireField(KEY_LAST_UPDATED)),
                    reportingMetadata = definition.require(KEY_REPORTING_METADATA).optMap(),
                    audience = audience,
                    exclusions = exclusions,
                    timeCriteria = TimeCriteria.fromJson(definition.opt(KEY_TIME_CRITERIA).optMap())
                )
            } catch (ex: JsonException) {
                UALog.e { "failed to parse Experiment from json $json" }
                return null
            }
        }
    }

    fun isActive(date: Long): Boolean {
        return timeCriteria?.meets(date) ?: true
    }
}
