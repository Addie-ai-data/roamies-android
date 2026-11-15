package com.kenkaiii.roamies

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

data class ScreenElement(
    val text: String,
    val contentDesc: String,
    val bounds: Rect,
    val clickable: Boolean,
    val packageName: String
)

class RoamieHandsAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RoamieHandsAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Track active window changes if needed
    }

    override fun onInterrupt() {
        instance = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    fun dumpActiveScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        collectNodes(root, elements)
        return elements
    }

    private fun collectNodes(node: AccessibilityNodeInfo?, list: MutableList<ScreenElement>) {
        if (node == null) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if ((text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) && bounds.width() > 0 && bounds.height() > 0) {
            list.add(
                ScreenElement(
                    text = text,
                    contentDesc = desc,
                    bounds = bounds,
                    clickable = node.isClickable,
                    packageName = node.packageName?.toString() ?: ""
                )
            )
        }

        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), list)
        }
    }

    fun findElementMatching(query: String): ScreenElement? {
        val q = query.lowercase().trim()
        val elements = dumpActiveScreenElements()
        return elements.firstOrNull {
            it.text.lowercase().contains(q) || it.contentDesc.lowercase().contains(q)
        }
    }

    fun performTapAt(x: Float, y: Float, onComplete: (() -> Unit)? = null): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete?.invoke()
            }
        }, null)
    }

    fun typeTextIntoFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
