package com.charles.livecaptionn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.charles.livecaptionn.service.CaptionForegroundService

/**
 * Reads only the visible transcript from Google Live Transcribe.
 * It does not record audio or inspect other apps.
 */
class GoogleLiveTranscribeAccessibilityService : AccessibilityService() {
    private var lastText = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != TARGET_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val root = rootInActiveWindow ?: event.source ?: return
        val texts = ArrayList<String>(24)
        val visited = intArrayOf(0)
        collectVisibleText(root, texts, depth = 0, visited = visited)
        val candidates = texts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_NODE_TEXT }
            .distinct()
            .toList()
        // Prefer a long non-button text node; short clickable labels are usually
        // controls ("Settings", "Clear", etc.), not recognized speech.
        val newest = candidates
            .filter { it.length >= 2 }
            .maxByOrNull { it.length } ?: return
        if (newest == lastText) return
        lastText = newest

        runCatching {
            startForegroundService(
                Intent(this, CaptionForegroundService::class.java)
                    .setAction(CaptionForegroundService.ACTION_EXTERNAL_TEXT)
                    .putExtra(CaptionForegroundService.EXTRA_EXTERNAL_TEXT, newest)
            )
        }.onFailure {
            Log.w(TAG, "Unable to forward transcript", it)
        }
    }

    private fun collectVisibleText(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > MAX_DEPTH || visited[0] >= NODE_BUDGET || !node.isVisibleToUser) return
        visited[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && (!node.isClickable || text.length >= 24)) out.add(text)
        for (i in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
            if (visited[0] >= NODE_BUDGET) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectVisibleText(child, out, depth + 1, visited)
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "GoogleTranscriptA11y"
        private const val TARGET_PACKAGE = "com.google.audio.hearing.visualization.accessibility.scribe"
        private const val NODE_BUDGET = 300
        private const val MAX_DEPTH = 12
        private const val MAX_CHILDREN = 80
        private const val MAX_NODE_TEXT = 2000
    }
}
