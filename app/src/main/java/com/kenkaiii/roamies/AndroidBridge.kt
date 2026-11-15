package com.kenkaiii.roamies

import android.webkit.JavascriptInterface

class AndroidBridge(
    private val onHitRectsUpdated: (List<android.graphics.Rect>) -> Unit,
    private val onHotChanged: (Boolean) -> Unit,
    private val onSidecarMessageReceived: (String) -> Unit,
    private val onVoiceRequested: () -> Unit
) {

    @JavascriptInterface
    fun setHitRects(rectsJson: String) {
        try {
            val arr = org.json.JSONArray(rectsJson)
            val rectList = mutableListOf<android.graphics.Rect>()
            // arr format: [x1, y1, w1, h1, x2, y2, w2, h2, ...]
            var i = 0
            while (i + 3 < arr.length()) {
                val x = arr.getInt(i)
                val y = arr.getInt(i + 1)
                val w = arr.getInt(i + 2)
                val h = arr.getInt(i + 3)
                rectList.add(android.graphics.Rect(x, y, x + w, y + h))
                i += 4
            }
            onHitRectsUpdated(rectList)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @JavascriptInterface
    fun setHot(hot: Boolean) {
        onHotChanged(hot)
    }

    @JavascriptInterface
    fun onSidecarMessage(msgJson: String) {
        onSidecarMessageReceived(msgJson)
    }

    @JavascriptInterface
    fun startVoiceInput() {
        onVoiceRequested()
    }

    @JavascriptInterface
    fun setTyping(typing: Boolean) {
        // Can adjust soft input mode if needed
    }
}
