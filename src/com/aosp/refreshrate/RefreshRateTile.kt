/*
 * Copyright (c) 2026 Pavel Dubrova <pashadubrova@gmail.com>
 *
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */

package com.aosp.refreshrate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlin.math.roundToInt

/**
 * Quick Settings tile for controlling the display refresh rate.
 *
 * Displays the current or forced refresh rate and allows cycling
 * between automatic mode and all supported fixed refresh rates.
 *
 * The tile observes display and system setting changes to keep
 * its state synchronized with the actual device configuration.
 */
class RefreshRateTile : TileService() {
    /** Main thread handler for delayed display updates. */
    private val handler = Handler(Looper.getMainLooper())

    /** Runnable used to update the tile after asynchronous mode changes. */
    private val updateAction =
        Runnable {
            update()
        }

    /** Function used to unregister display and settings observers. */
    private var unregister: (() -> Unit)? = null

    /** Cache key distinguishing refresh rate icons and their styles. */
    private data class IconKey(
        val rate: String,
        val filled: Boolean,
    )

    /** Cached icons to avoid repeatedly generating identical bitmaps. */
    private val iconCache = mutableMapOf<IconKey, Icon>()

    override fun onStartListening() {
        super.onStartListening()

        // Observe changes while the Quick Settings tile is active.
        unregister =
            RefreshRate.observe(
                this,
                handler,
            ) {
                update()
            }

        update()
    }

    override fun onStopListening() {
        // Release observers and pending callbacks when listening stops.
        unregister?.invoke()
        unregister = null

        handler.removeCallbacksAndMessages(null)

        super.onStopListening()
    }

    override fun onClick() {
        // Build a list containing automatic mode and supported fixed rates.
        val rates = RefreshRate.available(this)
        val options = listOf<Float?>(null) + rates

        if (options.size < 2) return

        val selected = RefreshRate.selected(this, rates)

        // Advance to the next option, wrapping around to automatic mode.
        val index = options.indexOf(selected).coerceAtLeast(0)
        val next = options[(index + 1) % options.size]

        if (!RefreshRate.set(this, next)) return

        update()

        // Recheck the actual display rate after the mode transition.
        RefreshRate.refreshSoon(
            handler,
            updateAction,
        )
    }

    /**
     * Updates the tile label, subtitle, icon and active state.
     *
     * Distinguishes automatic mode, an active forced rate and a pending
     * refresh rate change where the requested and actual rates differ.
     */
    private fun update() {
        val tile = qsTile ?: return

        val rates = RefreshRate.available(this)
        val selected = RefreshRate.selected(this, rates)
        val current = RefreshRate.current(this)

        tile.label = getString(R.string.tile_label)

        tile.subtitle =
            when {
                selected == null -> {
                    getString(
                        R.string.tile_auto,
                        RefreshRate.format(current),
                    )
                }

                kotlin.math.abs(selected - current) < 0.02f -> {
                    getString(
                        R.string.tile_fixed,
                        RefreshRate.format(selected),
                    )
                }

                else -> {
                    getString(
                        R.string.tile_fixed_now,
                        RefreshRate.format(selected),
                        RefreshRate.format(current),
                    )
                }
            }

        // Show the requested rate when fixed mode is enabled,
        // otherwise show the actual current refresh rate.
        val shown = selected ?: current

        tile.icon =
            if (selected == null) {
                autoIcon()
            } else if (shown > 0f) {
                rateIcon(
                    shown,
                    filled = true,
                )
            } else {
                Icon.createWithResource(
                    this,
                    R.drawable.ic_refresh_rate,
                )
            }

        // Disable the tile when no meaningful refresh rate options exist.
        tile.state =
            when {
                rates.size < 2 -> Tile.STATE_UNAVAILABLE
                selected == null -> Tile.STATE_INACTIVE
                else -> Tile.STATE_ACTIVE
            }

        tile.updateTile()
    }

    /**
     * Generates a custom refresh rate icon.
     *
     * Draws the rounded refresh rate value with a smaller Hz label
     * inside a badge.
     *
     * Filled icons use a solid badge with transparent text, indicating
     * a forced refresh rate. Outlined icons represent automatic mode
     * and the currently active display refresh rate.
     *
     * Generated icons are cached to reduce unnecessary bitmap allocations.
     *
     * @param rate Refresh rate to display.
     * @param filled Whether to draw a filled or outlined badge.
     * @return Generated refresh rate icon.
     */
    private fun rateIcon(
        rate: Float,
        filled: Boolean,
    ): Icon {
        val number = rate.roundToInt().toString()

        return iconCache.getOrPut(IconKey(number, filled)) {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Define the badge boundaries and corner radius.
            val box = RectF(4f, 10f, 92f, 86f)
            val radius = 16f

            // Create centered bold text paints for the rate and its unit.
            fun textPaint(px: Float) =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    textSize = px
                }

            val numberPaint = textPaint(if (number.length >= 3) 34f else 40f)
            val hzPaint = textPaint(22f)

            // Calculate text baselines to vertically center each line.
            fun baseline(
                p: Paint,
                centerY: Float,
            ) = centerY - (p.descent() + p.ascent()) / 2f

            val numberY = baseline(numberPaint, 36f)
            val hzY = baseline(hzPaint, 66f)

            if (filled) {
                // Draw a solid badge and clear the text from it.
                canvas.drawRoundRect(
                    box,
                    radius,
                    radius,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
                )

                val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                numberPaint.xfermode = clear
                hzPaint.xfermode = clear
            } else {
                // Draw an outlined badge for automatic mode.
                val stroke =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.STROKE
                        strokeWidth = 6f
                    }

                canvas.drawRoundRect(RectF(box).apply { inset(3f, 3f) }, radius, radius, stroke)
            }

            // Draw the refresh rate and Hz label.
            canvas.drawText(number, box.centerX(), numberY, numberPaint)
            canvas.drawText("Hz", box.centerX(), hzY, hzPaint)

            Icon.createWithBitmap(bmp)
        }
    }

    /**
     * Generates a custom Auto mode icon.
     *
     * Draws the word Auto centered inside an outlined rounded badge,
     * indicating that the display refresh rate is controlled automatically.
     *
     * Generated icons are cached to reduce unnecessary bitmap allocations.
     *
     * @return Generated Auto mode icon.
     */
    private fun autoIcon(): Icon =
        iconCache.getOrPut(IconKey("Auto", false)) {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            val box = RectF(4f, 10f, 92f, 86f)

            val stroke =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }

            canvas.drawRoundRect(box, 16f, 16f, stroke)

            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    textAlign = Paint.Align.CENTER
                    textSize = 27f
                }

            val baseline = box.centerY() - (paint.descent() + paint.ascent()) / 2f

            canvas.drawText("Auto", box.centerX(), baseline, paint)

            Icon.createWithBitmap(bmp)
        }
}
