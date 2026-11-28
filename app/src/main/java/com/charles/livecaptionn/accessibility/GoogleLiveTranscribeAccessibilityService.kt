package com.charles.livecaptionn.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads Android Live Caption text exposed by System Intelligence/System UI.
 * It forwards transcript text only inside this app process boundary; it does
 * not capture audio or inspect unrelated application windows.
 */
class GoogleLiveTranscribeAccessibilityService : AccessibilityService() {
    private var lastSnapshot = ""
    private var lastSystemStatus = ""
    private val pollHandler = Handler(Looper.getMainLooper())
    @Volatile private var polling = false

    /**
     * Some Live Caption revisions update the system caption surface without
     * delivering a useful accessibility event. A low-frequency fallback reads
     * only relevant system windows and broadcasts only a changed snapshot.
     */
    private val snapshotPoll = object : Runnable {
        override fun run() {
            if (!polling) return
            runCatching {
                val candidates = LinkedHashSet<String>()
                windows.take(8).forEach { window ->
                    val root = window.root ?: return@forEach
                    if (isRelevant(root.packageName?.toString().orEmpty())) {
                        collect(root, candidates, 0, intArrayOf(0))
                    }
                }
                val snapshot = CaptionSnapshotCandidateSelector.choose(
                    sourceCandidates = emptySet(),
                    eventTextCandidates = emptySet(),
                    windowCandidates = candidates,
                    previousSnapshot = lastSnapshot
                )
                if (snapshot.isNotBlank() && snapshot != lastSnapshot) {
                    lastSnapshot = snapshot
                    Log.d(TAG, "pollSnapshot changed=true chars=${snapshot.length} value=${snapshot.take(MAX_LOG_TEXT)}")
                    sendBroadcast(
                        Intent(ACTION_LIVE_CAPTION_TEXT)
                            .setPackage(packageName)
                            .putExtra(EXTRA_TEXT, snapshot)
                    )
                }
            }.onFailure { Log.w(TAG, "Caption snapshot poll failed", it) }
            if (polling) pollHandler.postDelayed(this, SNAPSHOT_POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        polling = true
        pollHandler.removeCallbacks(snapshotPoll)
        pollHandler.postDelayed(snapshotPoll, SNAPSHOT_POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        polling = false
        pollHandler.removeCallbacks(snapshotPoll)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isRelevant(event.packageName?.toString().orEmpty())) return
        if (event.eventType !in setOf(
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_ANNOUNCEMENT
            )
        ) return

        val eventPackage = event.packageName?.toString().orEmpty()
        Log.d(TAG, "event type=${AccessibilityEvent.eventTypeToString(event.eventType)} package=$eventPackage class=${event.className}")

        // Prefer the node tied to this accessibility event. Window-wide scans can
        // contain stale caption nodes; choosing the globally longest string may
        // freeze the app on an older, longer snapshot.
        val eventTextCandidates = LinkedHashSet<String>()
        event.text?.forEach {
            it?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(eventTextCandidates::add)
        }
        logCandidates("eventText", eventTextCandidates)

        val sourceCandidates = LinkedHashSet<String>()
        runCatching {
            event.source?.let { collect(it, sourceCandidates, 0, intArrayOf(0)) }
        }
        logCandidates("eventSource", sourceCandidates)

        val windowCandidates = LinkedHashSet<String>()
        runCatching {
            windows.take(8).forEach { window ->
                val root = window.root ?: return@forEach
                if (isRelevant(root.packageName?.toString().orEmpty())) {
                    collect(root, windowCandidates, 0, intArrayOf(0))
                }
            }
        }
        logCandidates("windowScan", windowCandidates)

        // Keep exact system diagnostic messages on a separate channel. They are
        // useful for troubleshooting, but must never enter the transcript/translator.
        val allCandidates = sourceCandidates + eventTextCandidates + windowCandidates
        val systemStatus = allCandidates.firstOrNull {
            CaptionSnapshotCandidateSelector.isKnownStatusMessage(it)
        }.orEmpty()
        if (systemStatus != lastSystemStatus) {
            lastSystemStatus = systemStatus
            sendBroadcast(
                Intent(ACTION_LIVE_CAPTION_STATUS)
                    .setPackage(packageName)
                    .putExtra(EXTRA_STATUS, systemStatus)
            )
        }

        val snapshot = CaptionSnapshotCandidateSelector.choose(
            sourceCandidates = sourceCandidates,
            eventTextCandidates = eventTextCandidates,
            windowCandidates = windowCandidates,
            previousSnapshot = lastSnapshot
        )
        Log.d(TAG, "selectedSnapshot chars=${snapshot.length} changed=${snapshot != lastSnapshot} value=${snapshot.take(MAX_LOG_TEXT)}")
        if (snapshot.isBlank()) {
            // A status-only accessibility update is not a caption-clear event.
            if (systemStatus.isNotBlank()) return
            if (lastSnapshot.isNotBlank()) {
                lastSnapshot = ""
                sendBroadcast(
                    Intent(ACTION_LIVE_CAPTION_TEXT)
                        .setPackage(packageName)
                        .putExtra(EXTRA_TEXT, "")
                )
            }
            return
        }
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        sendBroadcast(
            Intent(ACTION_LIVE_CAPTION_TEXT)
                .setPackage(packageName)
                .putExtra(EXTRA_TEXT, snapshot)
        )
    }

    private fun logCandidates(label: String, candidates: Collection<String>) {
        Log.d(TAG, "$label count=${candidates.size}: ${candidates.joinToString(" || ") { it.take(MAX_LOG_TEXT) }}")
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
            it.isNotEmpty() && it.length <= 20000 && containsJapanese(it)
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
        private const val TAG = "LiveCaptionAccessibility"
        private const val MAX_LOG_TEXT = 350

        const val ACTION_LIVE_CAPTION_TEXT =
            "com.charles.livecaptionn.action.LIVE_CAPTION_TEXT"
        const val EXTRA_TEXT = "live_caption_text"
        const val ACTION_LIVE_CAPTION_STATUS =
            "com.charles.livecaptionn.action.LIVE_CAPTION_STATUS"
        const val EXTRA_STATUS = "live_caption_status"
    }
}
