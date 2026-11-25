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

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Start the caption pipeline in external-transcript mode.
        runCatching {
            startForegroundService(
                Intent(this, CaptionForegroundService::class.java)
                    .setAction(CaptionForegroundService.ACTION_EXTERNAL_START)
            )
        }.onFailure {
            Log.w(TAG, "Unable to start caption bridge", it)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != TARGET_PACKAGE) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        val root = rootInActiveWindow ?: event.source ?: return
        val texts = ArrayList<String>(24)
        collectVisibleText(root, texts, depth = 0, budget = NODE_BUDGET)
        val snapshot = texts.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_NODE_TEXT }
            .distinct()
            .joinToString("\n")
        if (snapshot.isBlank() || snapshot == lastText) return

        // Live Transcribe usually places the newest recognized utterance last.
        val newest = snapshot.lineSequence().map { it.trim() }
            .lastOrNull { it.isNotEmpty() } ?: return
        if (newest == lastText) return
        lastText = newest

        runCatching {
            startService(
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
        budget: Int
    ) {
        if (depth > MAX_DEPTH || budget <= 0 || !node.isVisibleToUser) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        for (i in 0 until node.childCount.coerceAtMost(MAX_CHILDREN)) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectVisibleText(child, out, depth + 1, budget - 1)
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
