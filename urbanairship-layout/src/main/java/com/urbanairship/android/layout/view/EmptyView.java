/* Copyright Airship and Contributors */

package com.urbanairship.android.layout.view;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import com.urbanairship.android.layout.model.EmptyModel;
import com.urbanairship.android.layout.util.LayoutUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An empty view that can have a background and border.
 *
 * Useful for the nub on a banner or as dividers between items.
 *
 * @see EmptyModel
 */
public class EmptyView extends View implements BaseView<EmptyModel> {
    private EmptyModel model;

    public EmptyView(Context context) {
        super(context);
        init();
    }

    public EmptyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmptyView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public void init() {
        setId(generateViewId());
    }

    @NonNull
    public static EmptyView create(@NonNull Context context, @NonNull EmptyModel model) {
        EmptyView view = new EmptyView(context);
        view.setModel(model);
        return view;
    }

    @Override
    public void setModel(@NonNull EmptyModel model) {
        this.model = model;
        configure();
    }

    private void configure() {
        LayoutUtils.applyBorderAndBackground(this, model);
    }
}
