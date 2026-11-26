package com.charles.livecaptionn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads Android Live Caption text exposed by System Intelligence/System UI.
 * It forwards transcript text only inside this app process boundary; it does
 * not capture audio or inspect unrelated application windows.
 */
class GoogleLiveTranscribeAccessibilityService : AccessibilityService() {
    private var lastSnapshot = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRelevant(event.packageName?.toString().orEmpty())) return
        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_ANNOUNCEMENT
            )
        ) return

        val candidates = LinkedHashSet<String>()
        event.text?.forEach { it?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(candidates::add) }
        runCatching {
            event.source?.let { collect(it, candidates, 0, intArrayOf(0)) }
        }
        runCatching {
            windows.take(8).forEach { window ->
                val root = window.root ?: return@forEach
                if (isRelevant(root.packageName?.toString().orEmpty())) {
                    collect(root, candidates, 0, intArrayOf(0))
                }
            }
        }

        val snapshot = candidates.asSequence()
            .filter { it.length <= 2000 && containsJapanese(it) }
            .maxByOrNull { it.length }
            ?.trim().orEmpty()
        if (snapshot.isBlank() || snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        sendBroadcast(
            Intent(ACTION_LIVE_CAPTION_TEXT)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, snapshot)
        )
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > 10 || visited[0] >= 250 || !node.isVisibleToUser) return
        visited[0]++
        node.text?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 2000 && containsJapanese(it)
        }?.let(out::add)
        for (i in 0 until node.childCount.coerceAtMost(60)) {
            if (visited[0] >= 250) break
            runCatching { node.getChild(i) }.getOrNull()?.let {
                collect(it, out, depth + 1, visited)
            }
        }
    }

    private fun containsJapanese(value: String): Boolean = value.any {
        it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff'
    }

    private fun isRelevant(pkg: String): Boolean =
        pkg == "com.google.android.as" ||
            pkg == "com.android.systemui" ||
            pkg == "com.google.audio.hearing.visualization.accessibility.scribe"

    override fun onInterrupt() = Unit

    companion object {
        const val ACTION_LIVE_CAPTION_TEXT =
            "com.charles.livecaptionn.action.LIVE_CAPTION_TEXT"
        const val EXTRA_TEXT = "live_caption_text"
    }
}
