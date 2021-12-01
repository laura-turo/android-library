/* Copyright Airship and Contributors */

package com.urbanairship.android.layout.model;

import com.urbanairship.android.layout.property.Border;
import com.urbanairship.android.layout.property.Color;
import com.urbanairship.android.layout.property.FormInputType;
import com.urbanairship.android.layout.property.TextAppearance;
import com.urbanairship.android.layout.property.ViewType;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class TextInputModel extends BaseModel implements Identifiable, Accessible, Validatable {
    @NonNull
    private final String identifier;
    @NonNull
    private final FormInputType inputType;
    @NonNull
    private final TextAppearance textAppearance;
    @Nullable
    private final String hintText;
    @Nullable
    private final String contentDescription;
    private final boolean isRequired;

    public TextInputModel(
        @NonNull String identifier,
        @NonNull FormInputType inputType,
        @NonNull TextAppearance textAppearance,
        @Nullable String hintText,
        @Nullable String contentDescription,
        boolean isRequired,
        @Nullable Color backgroundColor,
        @Nullable Border border
    ) {
        super(ViewType.TEXT_INPUT, backgroundColor, border);

        this.identifier = identifier;
        this.inputType = inputType;
        this.textAppearance = textAppearance;
        this.hintText = hintText;
        this.contentDescription = contentDescription;
        this.isRequired = isRequired;
    }

    @NonNull
    public static TextInputModel fromJson(@NonNull JsonMap json) throws JsonException {
        String inputTypeString = json.opt("input_type").optString();
        FormInputType inputType = FormInputType.from(inputTypeString);
        String placeholder = json.opt("place_holder").getString();
        JsonMap textAppearanceJson = json.opt("text_appearance").optMap();
        TextAppearance textAppearance = TextAppearance.fromJson(textAppearanceJson);

        String identifier = Identifiable.identifierFromJson(json);
        String contentDescription = Accessible.contentDescriptionFromJson(json);
        boolean required = Validatable.requiredFromJson(json);
        Color backgroundColor = backgroundColorFromJson(json);
        Border border = borderFromJson(json);

        return new TextInputModel(
            identifier,
            inputType,
            textAppearance,
            placeholder,
            contentDescription,
            required,
            backgroundColor,
            border
        );
    }

    @NonNull
    public BaseModel getView() {
        return this;
    }

    @Override
    @NonNull
    public String getIdentifier() {
        return identifier;
    }

    @Override
    @Nullable
    public String getContentDescription() {
        return contentDescription;
    }

    @Override
    public boolean isRequired() {
        return isRequired;
    }

    @Override
    public boolean isValid() {
        // TODO: update this once the model is accepting input changes from the view.
        return true;
    }

    @NonNull
    public FormInputType getInputType() {
        return inputType;
    }

    @NonNull
    public TextAppearance getTextAppearance() {
        return textAppearance;
    }

    @Nullable
    public String getHintText() {
        return hintText;
    }
}
