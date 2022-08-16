/* Copyright Airship and Contributors */
package com.urbanairship.android.layout.model

import com.urbanairship.android.layout.ModelEnvironment
import com.urbanairship.android.layout.event.FormEvent
import com.urbanairship.android.layout.event.FormEvent.DataChange
import com.urbanairship.android.layout.event.ReportingEvent.FormResult
import com.urbanairship.android.layout.info.NpsFormControllerInfo
import com.urbanairship.android.layout.property.Border
import com.urbanairship.android.layout.property.Color
import com.urbanairship.android.layout.property.FormBehaviorType
import com.urbanairship.android.layout.property.ViewType
import com.urbanairship.android.layout.reporting.FormData

/**
 * Controller that manages NPS form views.
 */
internal class NpsFormController(
    final override val view: BaseModel,
    private val npsIdentifier: String,
    identifier: String,
    responseType: String?,
    submitBehavior: FormBehaviorType?,
    backgroundColor: Color? = null,
    border: Border? = null,
    environment: ModelEnvironment
) : BaseFormController<NpsFormControllerInfo>(
    ViewType.NPS_FORM_CONTROLLER,
    identifier = identifier,
    responseType = responseType,
    submitBehavior = submitBehavior,
    backgroundColor = backgroundColor,
    border = border,
    environment = environment
) {
    constructor(info: NpsFormControllerInfo, env: ModelEnvironment) : this(
        view = env.modelProvider.create(info.view, env),
        npsIdentifier = info.npsIdentifier,
        identifier = info.identifier,
        responseType = info.responseType,
        submitBehavior = info.submitBehavior,
        backgroundColor = info.backgroundColor,
        border = info.border,
        environment = env
    )

    override val children: List<BaseModel> = listOf(view)

    override val formType: String = "nps"

    override val initEvent: FormEvent.Init
        get() = FormEvent.Init(identifier, isFormValid)

    init {
        view.addListener(this)
    }

    override val formDataChangeEvent: DataChange
        get() = DataChange(
            FormData.Nps(identifier, responseType, npsIdentifier, formData.values),
            isFormValid,
            attributes
        )

    override val formResultEvent: FormResult
        get() = FormResult(
            FormData.Nps(identifier, responseType, npsIdentifier, formData.values),
            formInfo,
            attributes
        )
}
