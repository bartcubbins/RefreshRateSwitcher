/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object Visibility {
    /** Hides the Settings entry and the tile when the panel has nothing to choose from. */
    fun apply(c: Context) {
        val supported = RefreshRate.available(c).size >= 2
        val state =
            if (supported) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
        val pm = c.packageManager
        listOf(MainActivity::class.java, RefreshRateTile::class.java).forEach {
            pm.setComponentEnabledSetting(ComponentName(c, it), state, PackageManager.DONT_KILL_APP)
        }
    }
}
