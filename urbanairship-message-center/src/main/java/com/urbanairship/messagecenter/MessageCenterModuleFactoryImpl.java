package com.urbanairship.messagecenter;

import android.content.Context;

import com.urbanairship.AirshipConfigOptions;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.PrivacyManager;
import com.urbanairship.channel.AirshipChannel;
import com.urbanairship.modules.Module;
import com.urbanairship.modules.messagecenter.MessageCenterModuleFactory;
import com.urbanairship.push.PushManager;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

/**
 * Message Center module loader factory implementation.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class MessageCenterModuleFactoryImpl implements MessageCenterModuleFactory {

    @NonNull
    @Override
    public Module build(
            @NonNull Context context,
            @NonNull PreferenceDataStore dataStore,
            @NonNull PrivacyManager privacyManager,
            @NonNull AirshipChannel airshipChannel,
            @NonNull PushManager pushManager,
            @NonNull AirshipConfigOptions configOptions) {
        MessageCenter messageCenter = new MessageCenter(context, dataStore, privacyManager, airshipChannel, pushManager, configOptions);
        return Module.singleComponent(messageCenter, R.xml.ua_message_center_actions);
    }

    @NonNull
    @Override
    public String getAirshipVersion() {
        return BuildConfig.AIRSHIP_VERSION;
    }

    @NonNull
    @Override
    public String getPackageVersion() {
        return BuildConfig.SDK_VERSION;
    }

}
