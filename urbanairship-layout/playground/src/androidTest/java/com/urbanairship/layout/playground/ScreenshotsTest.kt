/* Copyright Airship and Contributors */

package com.urbanairship.layout.playground

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.core.app.launchActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.squareup.spoon.SpoonRule
import com.urbanairship.android.layout.ui.ModalActivity
import com.urbanairship.android.layout.ui.ModalActivity.EXTRA_MODAL_ASSET
import com.urbanairship.android.layout.util.ResourceUtils
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation test that uses Spoon to capture screenshots of all sample layouts.
 *
 * - Usage: `./gradlew :urbanairship-layout:playground:spoonDebugAndroidTest`
 * - Outputs a report with screenshots to: `urbanairship-layout/playground/build/spoon-output/debug/index.html`
 *
 * Tested on API 28 (pre scoped storage) -- buyer beware: this may not work on newer SDK levels without changes to
 * support storage scoping.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotsTest {
    companion object {
        private const val SAMPLE_LAYOUTS_PATH = "sample_layouts"
        private const val LAYOUT_DELAY_MS = 350L
    }

    @get:Rule
    val spoon = SpoonRule()
    @get:Rule
    val grantPermissionsRule: GrantPermissionRule = GrantPermissionRule.grant(WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE)

    @Test
    fun test() {
        val layouts = ResourceUtils.listJsonAssets(getApplicationContext(), SAMPLE_LAYOUTS_PATH)
        for (layout in layouts) {
            // Skipping web views for now because they aren't loading currently...
            if (layout.contains("webview")) continue

            val layoutName = layout.replace(".json", "")
            val intent = Intent(getApplicationContext(), ModalActivity::class.java).putExtra(EXTRA_MODAL_ASSET, layout)

            val scenario = launchActivity<ModalActivity>(intent)
            scenario.onActivity { activity ->
                SystemClock.sleep(LAYOUT_DELAY_MS)
                spoon.screenshot(activity, layoutName)
            }
            scenario.close()
        }
    }
}
