/* Copyright Airship and Contributors */

package com.urbanairship.iam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.urbanairship.UALog;
import com.urbanairship.experiment.ExperimentResult;
import com.urbanairship.iam.assets.Assets;
import com.urbanairship.json.JsonValue;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.WorkerThread;

/**
 * Helper class that keeps track of the schedule's adapter, coordinator, and schedule. Provides safe wrapper
 * methods around the adapter and coordinator calls.
 *
 * @hide
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
final class AdapterWrapper {

    static class DisplayException extends Exception {

        /**
         * Default constructor.
         *
         * @param message The exception message.
         * @param e The root exception.
         */
        DisplayException(String message, Exception e) {
            super(message, e);
        }

    }

    public final String scheduleId;
    public final JsonValue campaigns;
    public final JsonValue reportingContext;
    public final InAppMessage message;
    public final InAppMessageAdapter adapter;
    public final DisplayCoordinator coordinator;

    @Nullable
    public final ExperimentResult experimentResult;

    public boolean displayed = false;

    AdapterWrapper(@NonNull String scheduleId,
                   @Nullable JsonValue campaigns,
                   @Nullable JsonValue reportingContext,
                   @NonNull InAppMessage message,
                   @NonNull InAppMessageAdapter adapter,
                   @NonNull DisplayCoordinator coordinator,
                   @Nullable ExperimentResult experimentResult) {
        this.scheduleId = scheduleId;
        this.campaigns = campaigns == null ? JsonValue.NULL : campaigns;
        this.reportingContext = reportingContext == null ? JsonValue.NULL : reportingContext;
        this.message = message;
        this.adapter = adapter;
        this.coordinator = coordinator;
        this.experimentResult = experimentResult;
    }

    /**
     * Prepares the adapter.
     *
     * @param context The context.
     * @return The prepare result.
     */
    @InAppMessageAdapter.PrepareResult
    int prepare(@NonNull Context context, @NonNull Assets assets) {
        try {
            UALog.d("Preparing message for schedule %s", scheduleId);
            return adapter.onPrepare(context, assets);
        } catch (Exception e) {
            UALog.e(e, "AdapterWrapper - Exception during prepare(Context).");
            return InAppMessageAdapter.RETRY;
        }
    }

    /**
     * Checks if the adapter and coordinator are ready for display.
     *
     * @param context The context.
     * @return {@code true} if the coordinator and adapter are ready for display, otherwise {@code false}.
     */
    boolean isReady(@NonNull Context context) {
        try {
            return adapter.isReady(context) && coordinator.isReady();
        } catch (Exception e) {
            UALog.e(e, "AdapterWrapper - Exception during isReady(Activity).");
            return false;
        }
    }

    /**
     * Displays the in-app message.
     *
     * @throws DisplayException if the adapter throws an exception.
     */
    void display(@NonNull Context context) throws DisplayException {
        UALog.d("Displaying message for schedule %s", scheduleId);
        displayed = true;

        try {
            DisplayHandler displayHandler = new DisplayHandler(scheduleId, message.isReportingEnabled(), campaigns, reportingContext, experimentResult);
            adapter.onDisplay(context, displayHandler);
            coordinator.onDisplayStarted(message);
        } catch (Exception e) {
            throw new DisplayException("Adapter onDisplay(Activity, boolean, DisplayHandler) unexpected exception", e);
        }
    }

    /**
     * Notifies the coordinator the display is finished.
     */
    @MainThread
    void displayFinished() {
        UALog.d("Display finished for schedule %s", scheduleId);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                coordinator.onDisplayFinished(message);
            } catch (Exception e) {
                UALog.e(e, "AdapterWrapper - Exception during onDisplayFinished().");
            }
        });
    }

    /**
     * Cleans up the adapter.
     */
    @WorkerThread
    void adapterFinished(@NonNull Context context) {
        UALog.d("Adapter finished for schedule %s", scheduleId);
        try {
            adapter.onFinish(context);
        } catch (Exception e) {
            UALog.e(e, "AdapterWrapper - Exception during finish().");
        }
    }

}
