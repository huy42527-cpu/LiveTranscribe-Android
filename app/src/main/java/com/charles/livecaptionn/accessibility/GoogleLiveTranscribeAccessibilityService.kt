package com.charles.livecaptionn.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Temporary diagnostic probe for checking whether Android Live Caption exposes
 * Japanese transcript text through Accessibility. It only writes matching text
 * to local Logcat; it does not forward text into translation or record audio.
 *
 * Disable this service after the test because Accessibility can observe UI text.
 */
class GoogleLiveTranscribeAccessibilityService : AccessibilityService() {
    private var lastLogged = ""

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString().orEmpty()
        if (!isRelevantPackage(packageName)) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT
        ) return

        val texts = LinkedHashSet<String>()
        event.text?.forEach { value ->
            value?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        }
        runCatching {
            event.source?.let { source ->
                collectJapaneseText(source, texts, depth = 0, visited = intArrayOf(0))
            }
        }

        // Live Caption can be a separate system window, so inspect visible
        // accessibility windows too; keep the traversal bounded.
        runCatching {
            windows.take(MAX_WINDOWS).forEach { window ->
                window.root?.let { root ->
                    val rootPackage = root.packageName?.toString().orEmpty()
                    if (isRelevantPackage(rootPackage)) {
                        collectJapaneseText(root, texts, depth = 0, visited = intArrayOf(0))
                    }
                }
            }
        }

        val matches = texts.filter(::containsJapanese).distinct().take(MAX_LOG_ITEMS)
        if (matches.isEmpty()) return
        val joined = matches.joinToString(" | ").take(MAX_LOG_LENGTH)
        if (joined == lastLogged) return
        lastLogged = joined
        Log.i(TAG, "CAPTION_PROBE package=$packageName event=${event.eventType} text=$joined")
    }

    private fun collectJapaneseText(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > MAX_DEPTH || visited[0] >= NODE_BUDGET || !node.isVisibleToUser) return
        visited[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() && text.length <= MAX_NODE_TEXT && containsJapanese(text)) {
            out.add(text)
        }
        val count = node.childCount.coerceAtMost(MAX_CHILDREN)
        for (i in 0 until count) {
            if (visited[0] >= NODE_BUDGET) break
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            collectJapaneseText(child, out, depth + 1, visited)
        }
    }

    private fun containsJapanese(value: String): Boolean =
        value.any { ch ->
            ch in '\u3040'..'\u30ff' || ch in '\u3400'..'\u9fff'
        }

    private fun isRelevantPackage(packageName: String): Boolean =
        packageName == TARGET_TRANSCRIBE_PACKAGE ||
            packageName == ANDROID_SYSTEM_UI_PACKAGE ||
            packageName.contains("systemui", ignoreCase = true)

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "LiveCaptionA11yProbe"
        private const val TARGET_TRANSCRIBE_PACKAGE =
            "com.google.audio.hearing.visualization.accessibility.scribe"
        private const val ANDROID_SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val MAX_WINDOWS = 8
        private const val MAX_LOG_ITEMS = 12
        private const val MAX_LOG_LENGTH = 3000
        private const val NODE_BUDGET = 250
        private const val MAX_DEPTH = 10
        private const val MAX_CHILDREN = 60
        private const val MAX_NODE_TEXT = 2000
    }
}
