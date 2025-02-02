/* Copyright Airship and Contributors */

package com.urbanairship.featureflag

import com.urbanairship.analytics.Event
import com.urbanairship.json.JsonMap
import com.urbanairship.json.jsonMapOf

internal class FeatureFlagInteractionEvent private constructor(
    val data: JsonMap
) : Event() {

    @Throws
    internal constructor(flag: FeatureFlag) : this(
        jsonMapOf(
            "flag_name" to flag.name,
            "eligible" to flag.isEligible,
            "reporting_metadata" to requireNotNull(flag.reportingInfo?.reportingMetadata),
            "device" to flag.reportingInfo?.let {
                jsonMapOf(
                    "channel_id" to it.channelId,
                    "contact_id" to it.contactId,
                )
            }
        )
    )

    override fun getType(): String = "feature_flag_interaction"
    override fun getEventData(): JsonMap = data
}
