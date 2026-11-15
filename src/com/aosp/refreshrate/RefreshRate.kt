/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Utility for reading, changing and observing the display refresh rate.
 *
 * Uses the system settings min_refresh_rate and peak_refresh_rate
 * to control the forced refresh rate.
 *
 * A null selected rate represents automatic refresh rate selection.
 */
object RefreshRate {
    /** System setting controlling the minimum refresh rate. */
    const val MIN_RATE = "min_refresh_rate"

    /** System setting controlling the maximum refresh rate. */
    const val PEAK_RATE = "peak_refresh_rate"

    private const val TAG = "RefreshRate"

    /** Tolerance used when comparing floating-point refresh rates. */
    private const val EPSILON = 0.02f

    /**
     * Returns the default display.
     *
     * @return Default display or null if unavailable.
     */
    private fun display(context: Context): Display? =
        context
            .getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)

    /**
     * Returns refresh rates supported by the current display resolution.
     *
     * Only modes matching the current physical resolution are included.
     * Original Float values are preserved, while near-duplicate rates
     * are removed using rounded hundredths.
     *
     * @return Sorted list of supported refresh rates.
     */
    fun available(context: Context): List<Float> {
        val display = display(context) ?: return emptyList()
        val currentMode = display.mode

        return display.supportedModes
            .asSequence()
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }.map { it.refreshRate }
            .distinctBy { (it * 100f).roundToInt() }
            .sorted()
            .toList()
    }

    /**
     * Returns the currently forced refresh rate.
     *
     * @return Selected refresh rate or null when automatic mode is enabled.
     */
    fun selected(context: Context): Float? = selected(context, available(context))

    /**
     * Determines the forced refresh rate using system settings.
     *
     * A fixed refresh rate is considered active only when both minimum
     * and peak refresh rates have approximately the same positive value.
     *
     * @param context Context used to access system settings.
     * @param availableRates Refresh rates supported by the current display.
     * @return Matching supported rate or null for automatic mode.
     */
    fun selected(
        context: Context,
        availableRates: List<Float>,
    ): Float? {
        val resolver = context.contentResolver

        val min =
            Settings.System.getFloat(
                resolver,
                MIN_RATE,
                0f,
            )

        val peak =
            Settings.System.getFloat(
                resolver,
                PEAK_RATE,
                0f,
            )

        if (min <= 0f || abs(min - peak) > EPSILON) {
            return null
        }

        return availableRates.firstOrNull {
            abs(it - min) <= EPSILON
        }
    }

    /**
     * Returns the actual refresh rate currently reported by the display.
     *
     * Unlike [selected], this value represents the active display mode
     * and may differ from the requested refresh rate.
     *
     * @return Current refresh rate or 0 when the display is unavailable.
     */
    fun current(context: Context): Float = display(context)?.refreshRate ?: 0f

    /**
     * Checks whether the application can modify system settings.
     *
     * Requires the WRITE_SETTINGS permission to be granted by the user.
     */
    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * Sets the forced display refresh rate.
     *
     * A null value restores automatic refresh rate selection.
     * Both minimum and peak refresh rate settings are updated.
     *
     * @param context Context used to access system settings.
     * @param rate Requested refresh rate, or null for automatic mode.
     * @return True if both settings were successfully written.
     */
    fun set(
        context: Context,
        rate: Float?,
    ): Boolean {
        if (!canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS permission missing")
            return false
        }

        return try {
            val resolver = context.contentResolver
            val rates = available(context)

            // Reject rates that are not supported by the current resolution.
            if (rate != null &&
                rates.none {
                    abs(it - rate) <= EPSILON
                }
            ) {
                Log.w(TAG, "Unsupported refresh rate: $rate")
                return false
            }

            // Automatic mode uses a zero minimum and the highest available peak.
            val peak = rate ?: (rates.maxOrNull() ?: 60f)
            val min = rate ?: 0f

            val peakWritten =
                Settings.System.putFloat(
                    resolver,
                    PEAK_RATE,
                    peak,
                )

            val minWritten =
                Settings.System.putFloat(
                    resolver,
                    MIN_RATE,
                    min,
                )

            if (!peakWritten || !minWritten) {
                Log.e(TAG, "Failed to write refresh rate settings")
                return false
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set refresh rate", e)
            false
        }
    }

    /**
     * Registers observers for display and refresh rate setting changes.
     *
     * The callback is invoked whenever the default display changes
     * or either refresh rate system setting is modified.
     *
     * @param context Context used to register observers.
     * @param handler Handler used to dispatch callbacks.
     * @param callback Callback invoked when a relevant change occurs.
     * @return Function that unregisters all registered observers.
     */
    fun observe(
        context: Context,
        handler: Handler,
        callback: () -> Unit,
    ): () -> Unit {
        val displayManager =
            context.getSystemService(DisplayManager::class.java)

        // Observe changes to the minimum and peak refresh rate settings.
        val observer =
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    callback()
                }
            }

        // Observe changes to the default display configuration.
        val listener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit

                override fun onDisplayRemoved(displayId: Int) = Unit

                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        callback()
                    }
                }
            }

        displayManager.registerDisplayListener(listener, handler)

        context.contentResolver.apply {
            registerContentObserver(
                Settings.System.getUriFor(MIN_RATE),
                false,
                observer,
            )

            registerContentObserver(
                Settings.System.getUriFor(PEAK_RATE),
                false,
                observer,
            )
        }

        // Return a cleanup function to prevent observer leaks.
        return {
            displayManager.unregisterDisplayListener(listener)
            context.contentResolver.unregisterContentObserver(observer)
        }
    }

    /**
     * Schedules multiple delayed refresh checks.
     *
     * Display mode changes may be applied asynchronously, so several
     * checks are scheduled to detect the final active refresh rate.
     *
     * Existing callbacks for the same action are removed first.
     *
     * @param handler Handler used to schedule the checks.
     * @param action Runnable executed after each delay.
     */
    fun refreshSoon(
        handler: Handler,
        action: Runnable,
    ) {
        handler.removeCallbacks(action)

        longArrayOf(250, 750, 1500, 3000).forEach {
            handler.postDelayed(action, it)
        }
    }

    /**
     * Formats a refresh rate without unnecessary trailing zeros.
     *
     * @param rate Refresh rate in Hz.
     * @return Formatted numeric value.
     */
    fun format(rate: Float): String =
        String
            .format(
                java.util.Locale.US,
                "%.2f",
                rate,
            ).trimEnd('0')
            .trimEnd('.')

    /**
     * Formats a refresh rate with the Hz unit.
     *
     * @param rate Refresh rate in Hz.
     * @return Formatted refresh rate with unit.
     */
    fun formatHz(rate: Float): String = "${format(rate)} Hz"
}
