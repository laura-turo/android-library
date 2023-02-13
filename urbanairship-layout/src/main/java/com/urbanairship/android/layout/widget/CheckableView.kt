/* Copyright Airship and Contributors */
package com.urbanairship.android.layout.widget

import android.content.Context
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.annotation.Dimension
import androidx.appcompat.widget.SwitchCompat
import com.urbanairship.android.layout.model.CheckableModel
import com.urbanairship.android.layout.property.CheckboxStyle
import com.urbanairship.android.layout.property.SwitchStyle
import com.urbanairship.android.layout.property.ToggleType
import com.urbanairship.android.layout.util.LayoutUtils
import com.urbanairship.android.layout.util.ResourceUtils
import com.urbanairship.android.layout.util.ifNotEmpty
import com.urbanairship.android.layout.view.BaseView

internal abstract class CheckableView<M : CheckableModel<*>>(
    context: Context,
    protected val model: M
) : FrameLayout(context), BaseView {

    var checkedChangeListener: CheckableViewAdapter.OnCheckedChangeListener? = null

    lateinit var checkableView: CheckableViewAdapter<*>

    init {
        when (model.toggleType) {
            ToggleType.SWITCH -> configureSwitch(model.style as SwitchStyle)
            ToggleType.CHECKBOX -> configureCheckbox(model.style as CheckboxStyle)
        }
        LayoutUtils.applyBorderAndBackground(this, model)
        model.contentDescription.ifNotEmpty { checkableView.setContentDescription(it) }
    }

    private val minWidth: Int
        get() = when (model.toggleType) {
            ToggleType.CHECKBOX -> CHECKBOX_MIN_DIMENSION
            ToggleType.SWITCH -> SWITCH_MIN_WIDTH
        }
    private val minHeight: Int
        get() = when (model.toggleType) {
            ToggleType.CHECKBOX -> CHECKBOX_MIN_DIMENSION
            ToggleType.SWITCH -> SWITCH_MIN_HEIGHT
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minWidthDp = minWidth
        val minHeightDp = minHeight
        if (minWidthDp == NO_MIN_SIZE && minHeightDp == NO_MIN_SIZE) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        } else {
            var widthSpec = widthMeasureSpec
            var heightSpec = heightMeasureSpec
            if (minWidthDp != NO_MIN_SIZE) {
                val minWidth = ResourceUtils.dpToPx(context, minWidthDp).toInt()
                if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY) {
                    widthSpec = MeasureSpec.makeMeasureSpec(minWidth, MeasureSpec.EXACTLY)
                }
            }
            if (minHeightDp != NO_MIN_SIZE) {
                val minHeight = ResourceUtils.dpToPx(context, minHeightDp).toInt()
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.EXACTLY) {
                    heightSpec = MeasureSpec.makeMeasureSpec(minHeight, MeasureSpec.EXACTLY)
                }
            }
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    private fun configureSwitch(style: SwitchStyle) {
        val switchView = createSwitchView(style)
        switchView.id = model.checkableViewId
        LayoutUtils.applySwitchStyle(switchView, style)
        checkableView = CheckableViewAdapter.Switch(switchView)
        val lp = LayoutParams(MATCH_PARENT, MATCH_PARENT).apply {
            topMargin = -3
        }
        addView(switchView, lp)
    }

    private fun configureCheckbox(style: CheckboxStyle) {
        val checkboxView = createCheckboxView(style)
        checkboxView.id = model.checkableViewId
        LayoutUtils.applyBorderAndBackground(checkboxView, model)
        checkableView = CheckableViewAdapter.Checkbox(checkboxView)
        addView(checkboxView, MATCH_PARENT, MATCH_PARENT)
    }

    protected open fun createSwitchView(style: SwitchStyle): SwitchCompat {
        return SwitchCompat(context)
    }

    protected open fun createCheckboxView(style: CheckboxStyle): ShapeButton {
        val checked = style.bindings.selected
        val unchecked = style.bindings.unselected
        return ShapeButton(context, checked.shapes, unchecked.shapes, checked.icon, unchecked.icon)
    }

    protected fun setCheckedInternal(isChecked: Boolean) {
        checkableView.setOnCheckedChangeListener(null)
        checkableView.isChecked = isChecked
        checkableView.setOnCheckedChangeListener(checkedChangeListener)
    }

    override fun setEnabled(isEnabled: Boolean) {
        checkableView.setEnabled(isEnabled)
    }

    companion object {
        @Dimension(unit = Dimension.DP)
        private const val CHECKBOX_MIN_DIMENSION = 24

        @Dimension(unit = Dimension.DP)
        private const val SWITCH_MIN_HEIGHT = 24

        @Dimension(unit = Dimension.DP)
        private const val SWITCH_MIN_WIDTH = 48
        private const val NO_MIN_SIZE = -1
    }
}
