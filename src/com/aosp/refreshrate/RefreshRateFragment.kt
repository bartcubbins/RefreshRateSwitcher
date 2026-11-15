/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.android.settingslib.widget.SelectorWithWidgetPreference
import com.android.settingslib.widget.SettingsBasePreferenceFragment

/**
 * Preference screen for selecting the display refresh rate.
 *
 * Displays the current refresh rate and provides options for automatic
 * mode and all refresh rates supported by the current display resolution.
 *
 * The screen is rebuilt when the available refresh rates change.
 */
class RefreshRateFragment : SettingsBasePreferenceFragment() {
    /** Main thread handler used for display update callbacks. */
    private val handler = Handler(Looper.getMainLooper())

    /** Runnable used to refresh the preference screen after mode changes. */
    private val updateAction =
        Runnable {
            update()
        }

    /** Function used to unregister display and settings observers. */
    private var unregister: (() -> Unit)? = null

    /** Preference displaying the actual current refresh rate. */
    private var currentPref: Preference? = null

    /** Map of selectable refresh rates and their corresponding preferences. */
    private val rows =
        mutableMapOf<Float?, SelectorWithWidgetPreference>()

    /** Refresh rates used to build the current preference list. */
    private var builtFor: List<Float>? = null

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        // Create an empty preference screen programmatically.
        preferenceScreen =
            preferenceManager.createPreferenceScreen(requireContext())
    }

    override fun onStart() {
        super.onStart()

        // Start observing display and system setting changes.
        unregister =
            RefreshRate.observe(
                requireContext(),
                handler,
            ) {
                update()
            }

        update()
    }

    override fun onStop() {
        // Unregister observers and cancel pending refresh checks.
        unregister?.invoke()
        unregister = null

        handler.removeCallbacksAndMessages(null)

        super.onStop()
    }

    /**
     * Creates or rebuilds the preference list when supported rates change.
     *
     * Includes an automatic mode option followed by all available
     * fixed refresh rates.
     *
     * @param context Context used to create preferences.
     */
    private fun ensureBuilt(context: Context) {
        val rates = RefreshRate.available(context)

        // Avoid rebuilding the screen when the available rates are unchanged.
        if (builtFor == rates) return

        builtFor = rates

        preferenceScreen.removeAll()
        rows.clear()

        // Display the actual refresh rate independently of the selected option.
        currentPref =
            Preference(context).apply {
                isSelectable = false
            }

        preferenceScreen.addPreference(currentPref!!)

        // Group all selectable refresh rate options.
        val category =
            PreferenceCategory(context).apply {
                title = getString(R.string.title_refresh_rate)
            }

        preferenceScreen.addPreference(category)

        // Null represents automatic mode.
        (listOf<Float?>(null) + rates).forEach { rate ->

            val row =
                SelectorWithWidgetPreference(context).apply {
                    title =
                        if (rate == null) {
                            getString(R.string.mode_auto)
                        } else {
                            RefreshRate.formatHz(rate)
                        }

                    setOnClickListener {
                        selectRate(rate)
                    }
                }

            rows[rate] = row
            category.addPreference(row)
        }
    }

    /**
     * Applies the requested refresh rate.
     *
     * Shows an error message if the settings cannot be modified.
     * After a successful change, schedules additional updates to account
     * for asynchronous display mode transitions.
     *
     * @param rate Requested refresh rate, or null for automatic mode.
     */
    private fun selectRate(rate: Float?) {
        val context = context ?: return

        if (!RefreshRate.set(context, rate)) {
            Toast
                .makeText(
                    context,
                    R.string.write_failed,
                    Toast.LENGTH_LONG,
                ).show()

            return
        }

        update()

        RefreshRate.refreshSoon(
            handler,
            updateAction,
        )
    }

    /**
     * Updates the current refresh rate and selected preference.
     *
     * Rebuilds the preference list if the available refresh rates
     * have changed since the previous update.
     */
    private fun update() {
        val context = context ?: return

        ensureBuilt(context)

        val current = RefreshRate.current(context)

        val currentText =
            getString(
                R.string.current_rate,
                RefreshRate.format(current),
            )

        // Update the displayed live refresh rate only when necessary.
        if (currentPref?.title != currentText) {
            currentPref?.title = currentText
        }

        val selected =
            RefreshRate.selected(
                context,
                builtFor.orEmpty(),
            )

        // Synchronize the checked state with the actual system settings.
        rows.forEach { (rate, row) ->
            val checked =
                when {
                    rate == null -> selected == null
                    selected == null -> false
                    else -> kotlin.math.abs(rate - selected) < 0.02f
                }

            if (row.isChecked != checked) {
                row.isChecked = checked
            }
        }
    }
}
