/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Visibility.apply(context)
    }
}
