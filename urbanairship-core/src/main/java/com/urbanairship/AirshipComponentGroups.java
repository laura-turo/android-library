/* Copyright Airship and Contributors */

package com.urbanairship;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import androidx.annotation.IntDef;
import androidx.annotation.RestrictTo;

/**
 * Airship component groups. Used to group components for remote-config.
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface AirshipComponentGroups {


    @IntDef({ NONE, PUSH, ANALYTICS, MESSAGE_CENTER, IN_APP, ACTION_AUTOMATION, NAMED_USER, LOCATION,
            CHANNEL, CHAT, CONTACT, PREFERENCE_CENTER, LIVE_UPDATE, EXPERIMENT, FEATURE_FLAGS })
    @Retention(RetentionPolicy.SOURCE)
    @interface Group {
    }

    int NONE = -1;
    int PUSH = 0;
    int ANALYTICS = 1;
    int MESSAGE_CENTER = 2;
    int IN_APP = 3;
    int ACTION_AUTOMATION = 4;
    int NAMED_USER = 5;
    int LOCATION = 6;
    int CHANNEL = 7;
    int CHAT = 8;
    int CONTACT = 9;
    int PREFERENCE_CENTER = 10;
    int LIVE_UPDATE = 11;
    int EXPERIMENT = 12;
    int FEATURE_FLAGS = 13;

}
