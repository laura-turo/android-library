/* Copyright Urban Airship and Contributors */

package com.urbanairship.sample;

import android.app.Application;
import android.os.StrictMode;

public class SampleApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build());

            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectUntaggedSockets()
                    .penaltyLog()
                    .build());
        }
    }

}
