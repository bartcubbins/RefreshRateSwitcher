/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.os.Bundle
import android.widget.FrameLayout
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.ExpressiveDesignEnabledProvider

/**
 * Main activity hosting the refresh rate preferences.
 *
 * Uses the SettingsLib collapsing toolbar and enables
 * the expressive design provided by SettingsLib.
 */
class MainActivity :
    CollapsingToolbarBaseActivity(),
    ExpressiveDesignEnabledProvider {
    /** Enables the expressive SettingsLib design. */
    override fun isExpressiveDesignEnabled(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.title_refresh_rate)

        // Create the container used to host the preference fragment.
        val container =
            FrameLayout(this).apply {
                id = R.id.fragment_container
            }

        setContentView(container)

        // Add the fragment only when the activity is created for the first time.
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(
                    R.id.fragment_container,
                    RefreshRateFragment(),
                ).commit()
        }
    }
}
