package com.kenkaiii.roamies

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.widget.FrameLayout

class TouchOverlayView(context: Context) : FrameLayout(context) {

    var hitRects: List<Rect> = emptyList()
    var onEmptyTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x.toInt()
        val y = ev.y.toInt()

        // Check if touch point falls into any active element (Roamie, speech bubble, ink cards, buttons)
        val hit = hitRects.any { it.contains(x, y) }

        return if (hit) {
            super.dispatchTouchEvent(ev)
        } else {
            onEmptyTouch?.invoke()
            false
        }
    }
}
