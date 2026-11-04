package com.github.libretube.ui.listeners

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.media3.common.Player
import com.github.libretube.helpers.PlayerHelper
import kotlin.math.min

/**
 * Touch listener that performs an initial seek action on press and keeps seeking
 * faster the longer the view remains pressed.
 *
 * The position to seek to is tracked locally, so that the seeking advances
 * independently of the buffering state of the player. Once the repeated seeking
 * starts, [onHoldStart] is invoked, and [onHoldEnd] when the repeated seeking
 * ends again, e.g. to mute the player while seeking.
 *
 * As the touch events are consumed, the click listener of the view stays
 * functional for accessibility services.
 *
 * @param direction the direction to seek in, 1 to seek forwards and -1 to rewind
 */
class ContinuousSeekListener(
    private val direction: Int,
    private val getPlayer: () -> Player?,
    private val onHoldStart: () -> Unit = {},
    private val onHoldEnd: () -> Unit = {}
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var isHeld = false
    private var isHoldActive = false
    private var target: View? = null

    // the locally tracked position to seek the player to and when the hold started,
    // a negative target position means it isn't seeded with the player position yet
    private var targetPositionMs = -1L
    private var holdStartTime = 0L

    private val seekRunnable = object : Runnable {
        override fun run() {
            val view = target
            // stop seeking if the press ended or the view got detached in the meantime,
            // as events like ACTION_UP are not guaranteed to be delivered to detached views
            if (!isHeld || view == null || !view.isAttachedToWindow) {
                isHeld = false
                view?.isPressed = false
                if (isHoldActive) endHold()
                return
            }
            if (!isHoldActive) {
                // the button is held down long enough for the repeated seeking to start
                isHoldActive = true
                onHoldStart()
            }
            seekByStep()
            handler.postDelayed(this, REPEAT_INTERVAL)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // remove pending seeks to never run multiple repeat chains at once
                handler.removeCallbacks(seekRunnable)
                isHeld = true
                target = v
                holdStartTime = SystemClock.elapsedRealtime()
                // anchor the pressed state ripple at the touch position, as the
                // consumed touch events skip the default handling doing this
                v.background?.setHotspot(event.x, event.y)
                v.isPressed = true
                // perform the initial seek of one increment, further seeks follow
                // once the button is held
                seekByIncrement()
                handler.postDelayed(seekRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                // pause the seeking when the finger leaves the button and resume it
                // when it enters the button again
                val isInside = event.x >= 0 && event.x < v.width &&
                    event.y >= 0 && event.y < v.height
                if (isHeld != isInside) {
                    isHeld = isInside
                    if (isInside) {
                        v.background?.setHotspot(event.x, event.y)
                    }
                    v.isPressed = isInside
                    if (isInside) {
                        handler.postDelayed(seekRunnable, REPEAT_INTERVAL)
                    } else {
                        handler.removeCallbacks(seekRunnable)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHeld = false
                target = null
                v.isPressed = false
                handler.removeCallbacks(seekRunnable)
                if (isHoldActive) endHold()
            }
        }
        return true
    }

    private fun endHold() {
        isHoldActive = false
        onHoldEnd()
    }

    /**
     * Perform an initial seek by one full seek increment, like a single tap does.
     */
    private fun seekByIncrement() {
        val player = getPlayer() ?: return

        targetPositionMs = player.currentPosition + direction * PlayerHelper.seekIncrement
        clampTargetPosition(player)
        player.seekTo(targetPositionMs)
    }

    /**
     * Advance the locally tracked target position and seek the player to it, so
     * that the seeking doesn't wait for the player to buffer the seeked
     * positions. The speed grows with the hold duration but is capped, so that
     * it stays possible to release the button at the wanted position.
     */
    private fun seekByStep() {
        val player = getPlayer() ?: return

        if (targetPositionMs < 0) {
            // the position couldn't be seeded on press yet, e.g. because the
            // player wasn't connected, so seed it now
            targetPositionMs = player.currentPosition
        }

        val heldSeconds = (SystemClock.elapsedRealtime() - holdStartTime) / 1000f
        val seekSpeed = min(SPEED_START + SPEED_GROWTH * heldSeconds, SPEED_MAX)
        targetPositionMs += direction *
            (PlayerHelper.seekIncrement * seekSpeed * REPEAT_INTERVAL / 1000).toLong()
        clampTargetPosition(player)
        player.seekTo(targetPositionMs)
    }

    private fun clampTargetPosition(player: Player) {
        val duration = player.duration
        if (duration > 0) {
            targetPositionMs = targetPositionMs.coerceIn(0, duration)
        }
    }

    companion object {
        // how often the target position gets advanced while a button is held down
        private const val REPEAT_INTERVAL = 100L

        // the seeking speed in seek increments per second, it starts at SPEED_START,
        // grows by SPEED_GROWTH per held second and is capped at SPEED_MAX
        private const val SPEED_START = 6f
        private const val SPEED_GROWTH = 3f
        private const val SPEED_MAX = 12f
    }
}
