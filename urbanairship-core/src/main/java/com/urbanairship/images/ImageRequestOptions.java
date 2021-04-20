/* Copyright Airship and Contributors */

package com.urbanairship.images;

import com.urbanairship.images.ImageLoader.ImageLoadedCallback;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Image request options.
 */
public class ImageRequestOptions {

    private final int placeHolder;
    private final String url;
    @Nullable
    private final ImageLoadedCallback callback;

    private ImageRequestOptions(@NonNull Builder builder) {
        this.url = builder.url;
        this.placeHolder = builder.placeHolder;
        this.callback = builder.callback;
    }

    /**
     * Gets the place holder.
     *
     * @return The place holder.
     */
    @DrawableRes
    public int getPlaceHolder() {
        return placeHolder;
    }

    /**
     * Gets the image URL.
     *
     * @return The image URL.
     */
    @Nullable
    public String getUrl() {
        return url;
    }

    /**
     * Gets the {@link ImageLoadedCallback}, if one was set.
     */
    @Nullable
    public ImageLoadedCallback getCallback() {
        return callback;
    }

    /**
     * Creates a new builder.
     *
     * @param url The image URL.
     * @return The builder.
     */
    @NonNull
    public static Builder newBuilder(@Nullable String url) {
        return new Builder(url);
    }

    /**
     * Image request option builder.
     */
    public static class Builder {

        private int placeHolder;
        private final String url;
        private ImageLoadedCallback callback;

        private Builder(@Nullable String url) {
            this.url = url;
        }

        /**
         * Sets the place holder.
         *
         * @param placeHolder The place holder resource.
         * @return The builder.
         */
        @NonNull
        public Builder setPlaceHolder(@DrawableRes int placeHolder) {
            this.placeHolder = placeHolder;
            return this;
        }

        /**
         * Sets an {@link ImageLoadedCallback} to notify the caller when the image has been loaded.
         */
        @NonNull
        public Builder setImageLoadedCallback(ImageLoadedCallback callback) {
            this.callback = callback;
            return this;
        }

        /**
         * Builds the image request options.
         *
         * @return The image request options.
         */
        @NonNull
        public ImageRequestOptions build() {
            return new ImageRequestOptions(this);
        }

    }

}
