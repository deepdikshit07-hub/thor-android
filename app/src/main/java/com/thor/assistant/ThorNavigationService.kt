package com.thor.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.SystemClock
import java.util.Locale

/**
 * User-enabled, visible-screen helper for the explicitly requested YouTube task.
 * It only acts while YouTube is the foreground app and a THOR task is pending.
 */
class ThorNavigationService : AccessibilityService() {
    private var lastActionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("thor_navigation", MODE_PRIVATE)
        if (prefs.getString("task", "") != "YOUTUBE_FIFTH_DOWNLOAD") return
        if (event.packageName?.toString() != "com.google.android.youtube") return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 650L) return
        val root = rootInActiveWindow ?: return
        when (prefs.getString("stage", "")) {
            "WAIT_DOWNLOADS" -> {
                val downloads = findTextNode(root, "downloads")
                if (downloads != null && clickNodeOrParent(downloads)) {
                    prefs.edit().putString("stage", "WAIT_VIDEO").putString("seen_items", "").apply()
                    lastActionAt = now
                    Toast.makeText(this, "THOR: Opened Downloads. Finding item 5…", Toast.LENGTH_SHORT).show()
                }
            }
            "WAIT_VIDEO" -> {
                val visible = collectVideoRows(root)
                if (visible.isEmpty()) return
                val seen = prefs.getString("seen_items", "").orEmpty()
                    .split("\n").filter { it.isNotBlank() }.toMutableList()
                visible.forEach { row ->
                    if (seen.none { it.equals(row.first, ignoreCase = true) }) seen.add(row.first)
                }
                prefs.edit().putString("seen_items", seen.joinToString("\n")).apply()

                if (seen.size >= 5) {
                    val fifthLabel = seen[4]
                    val fifthVisible = visible.firstOrNull { it.first.equals(fifthLabel, ignoreCase = true) }
                    if (fifthVisible != null && fifthVisible.second.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        prefs.edit().putString("stage", "DONE").putString("task", "").apply()
                        lastActionAt = now
                        Toast.makeText(this, "THOR selected the fifth visible Downloads entry. Check playback.", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                scrollDownloads(root)
                lastActionAt = now
            }
        }
    }

    private fun findTextNode(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()
            if (label.trim().equals(target, ignoreCase = true)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun collectVideoRows(root: AccessibilityNodeInfo): List<Pair<String, AccessibilityNodeInfo>> {
        val screenHeight = resources.displayMetrics.heightPixels
        val found = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val excluded = setOf("home", "shorts", "subscriptions", "you", "downloads", "search", "create")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isClickable) {
                val label = (node.contentDescription?.toString() ?: node.text?.toString()).orEmpty()
                    .replace(Regex("\\s+"), " ").trim()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val normalized = label.lowercase(Locale.ROOT)
                val useful = label.length >= 3 &&
                    normalized !in excluded &&
                    !normalized.contains("more options") &&
                    !normalized.contains("downloaded") &&
                    !normalized.contains("pause") &&
                    !normalized.contains("play button") &&
                    bounds.top > screenHeight * 0.12 &&
                    bounds.bottom < screenHeight * 0.92 &&
                    bounds.height() > 24
                if (useful && found.none { it.first.equals(label, ignoreCase = true) }) {
                    found.add(label to node)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return found.sortedBy {
            val rect = Rect()
            it.second.getBoundsInScreen(rect)
            rect.top
        }
    }

    private fun scrollDownloads(root: AccessibilityNodeInfo) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    override fun onInterrupt() = Unit
}
