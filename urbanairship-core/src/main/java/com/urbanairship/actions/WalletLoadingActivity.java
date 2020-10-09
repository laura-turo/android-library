/* Copyright Airship and Contributors */

package com.urbanairship.actions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.urbanairship.AirshipExecutors;
import com.urbanairship.Logger;
import com.urbanairship.R;
import com.urbanairship.activity.ThemedActivity;
import com.urbanairship.http.Request;
import com.urbanairship.http.RequestException;
import com.urbanairship.http.Response;
import com.urbanairship.http.ResponseParser;
import com.urbanairship.util.UAHttpStatusUtil;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class WalletLoadingActivity extends ThemedActivity {

    private final MutableLiveData<Result> liveData = new MutableLiveData<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.ua_activity_wallet_loading);

        URL url = null;

        try {
            Uri uri = getIntent().getData();
            if (uri != null) {
                url = new URL(getIntent().getData().toString());
            }
        } catch (MalformedURLException e) {
            Logger.warn("The wallet URL is incorrect, finishing operation.", e);
            finish();
            return;
        }

        if (url == null) {
            Logger.warn("User URI null, unable to process link.");
            finish();
            return;
        }

        liveData.observe(this, new Observer<Result>() {
            @Override
            public void onChanged(Result result) {
                if (result.exception != null || result.uri == null) {
                    finish();
                } else {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, result.uri);
                    startActivity(browserIntent);
                }
            }
        });
        resolveWalletUrl(url);
    }

    private void resolveWalletUrl(@NonNull final URL url) {
        AirshipExecutors.THREAD_POOL_EXECUTOR.submit(new Runnable() {

            @Override
            public void run() {
                try {
                    Logger.debug("Runner starting");
                    Response<String> response = new Request()
                            .setOperation("GET", url)
                            .setInstanceFollowRedirects(false)
                            .execute(new ResponseParser<String>() {
                                @Override
                                public String parseResponse(int status, @Nullable Map<String, List<String>> headers, @Nullable String responseBody) {
                                    if (UAHttpStatusUtil.inRedirectionRange(status)) {
                                        if (headers != null && headers.get("Location") != null) {
                                            return headers.get("Location").get(0);
                                        }
                                    }
                                    return null;
                                }
                            });
                    if (response.getResult() != null) {
                        liveData.postValue(new Result(Uri.parse(response.getResponseHeader("Location")), null));
                    } else {
                        Logger.warn("No result found for Wallet URL, finishing action.");
                        liveData.postValue(new Result(null, null));
                    }
                } catch (RequestException e) {
                    liveData.postValue(new Result(null , e));
                }
            }
        });
    }

    private static class Result {
        Uri uri;
        Exception exception;

        public Result(Uri uri, Exception exception) {
            this.uri = uri;
            this.exception = exception;
        }
    }
}
