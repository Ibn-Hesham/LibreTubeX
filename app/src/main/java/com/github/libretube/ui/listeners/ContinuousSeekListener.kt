package com.github.libretube.ui.listeners

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Touch listener that performs an initial seek action on press and keeps repeating
 * it in a fixed interval while the view remains pressed.
 *
 * Once the repeated seeking starts, [onHoldStart] is invoked, and [onHoldEnd] when
 * the repeated seeking ends again, e.g. to mute the player while seeking.
 *
 * As the touch events are consumed, the click listener of the view stays functional
 * for accessibility services.
 */
class ContinuousSeekListener(
    private val onSeek: () -> Unit,
    private val onHoldStart: () -> Unit = {},
    private val onHoldEnd: () -> Unit = {}
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private var isHeld = false
    private var isHoldActive = false
    private var target: View? = null

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
            onSeek()
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
                v.isPressed = true
                // perform the initial seek, further seeks follow once the button is held
                onSeek()
                handler.postDelayed(seekRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                // pause the seeking when the finger leaves the button and resume it when
                // it enters the button again
                val isInside = event.x >= 0 && event.x < v.width &&
                    event.y >= 0 && event.y < v.height
                if (isHeld != isInside) {
                    isHeld = isInside
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

    companion object {
        private const val REPEAT_INTERVAL = 250L
    }
}
