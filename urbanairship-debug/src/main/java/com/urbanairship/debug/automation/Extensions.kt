package com.urbanairship.debug.automation

import android.content.Context
import android.os.Bundle
import com.urbanairship.automation.Trigger
import com.urbanairship.debug.R
import com.urbanairship.json.JsonValue
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

fun Trigger.triggerTitle(context: Context): String {
    return when (type) {
        Trigger.LIFE_CYCLE_FOREGROUND -> context.getString(R.string.ua_iaa_trigger_lifecycle_foreground)
        Trigger.LIFE_CYCLE_BACKGROUND -> context.getString(R.string.ua_iaa_trigger_lifecycle_background)
        Trigger.REGION_ENTER -> context.getString(R.string.ua_iaa_trigger_region_enter)
        Trigger.REGION_EXIT -> context.getString(R.string.ua_iaa_trigger_region_exit)
        Trigger.CUSTOM_EVENT_COUNT -> context.getString(R.string.ua_iaa_trigger_custom_event_count)
        Trigger.CUSTOM_EVENT_VALUE -> context.getString(R.string.ua_iaa_trigger_custom_event_value)
        Trigger.SCREEN_VIEW -> context.getString(R.string.ua_iaa_trigger_screen_view)
        Trigger.LIFE_CYCLE_APP_INIT -> context.getString(R.string.ua_iaa_trigger_lifecycle_app_init)
        Trigger.ACTIVE_SESSION -> context.getString(R.string.ua_iaa_trigger_active_session)
        Trigger.VERSION -> context.getString(R.string.ua_iaa_trigger_version)
        Trigger.FEATURE_FLAG_INTERACTED -> context.getString(R.string.ua_iaa_trigger_feature_flag_interacted_event_value)
        else -> context.getString(R.string.ua_iaa_trigger_unknown)
    }
}

fun Int.toHex(): String {
    return String.format("#%06X", 0xFFFFFF and this)
}

fun <T> Bundle.parseJson(key: String, apply: (JsonValue) -> T): T {
    return apply(JsonValue.parseString(getString(key)))
}

fun Long.formatDuration(context: Context, source: TimeUnit): String {
    val strings = mutableListOf<String>()
    var remaining = source.toMillis(this)

    val days = TimeUnit.MILLISECONDS.toDays(remaining)
    if (days > 0) {
        remaining -= TimeUnit.DAYS.toMillis(days)
        strings.add(context.resources.getQuantityString(R.plurals.ua_debug_days_quantity, days.toInt(), days.toString()))
    }

    val hours = TimeUnit.MILLISECONDS.toHours(remaining)
    if (hours > 0) {
        remaining -= TimeUnit.HOURS.toMillis(hours)
        strings.add(context.resources.getQuantityString(R.plurals.ua_debug_hours_quantity, hours.toInt(), hours.toString()))
    }

    val minutes = TimeUnit.MILLISECONDS.toMinutes(remaining)
    if (minutes > 0) {
        remaining -= TimeUnit.MINUTES.toMillis(hours)
        strings.add(context.resources.getQuantityString(R.plurals.ua_debug_minutes_quantity, minutes.toInt(), minutes.toString()))
    }

    if (remaining > 0) {
        val seconds = remaining / 1000.0
        strings.add(context.resources.getQuantityString(R.plurals.ua_debug_seconds_quantity, ceil(seconds).toInt(), "%.3f".format(seconds)))
    }

    return strings.joinToString(" ")
}
