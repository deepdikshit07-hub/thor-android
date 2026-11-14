package com.thor.assistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.SystemClock
import java.util.Locale

/**
 * Narrow, user-enabled screen navigation for explicit THOR commands.
 * It only interacts with YouTube or Instagram while a matching task is pending.
 */
class ThorNavigationService : AccessibilityService() {
    private var lastActionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val prefs = getSharedPreferences("thor_navigation", MODE_PRIVATE)
        val task = prefs.getString("task", "").orEmpty()
        if (task.isBlank()) return

        val createdAt = prefs.getLong("created_at", 0L)
        if (createdAt > 0L && System.currentTimeMillis() - createdAt > 90_000L) {
            prefs.edit().clear().apply()
            Toast.makeText(this, "THOR navigation stopped: timed out.", Toast.LENGTH_SHORT).show()
            return
        }

        val expectedPackage = when (task) {
            "INSTAGRAM_REELS" -> "com.instagram.android"
            "YOUTUBE_DOWNLOADS", "YOUTUBE_DOWNLOAD_INDEX" -> "com.google.android.youtube"
            else -> return
        }
        if (event.packageName?.toString() != expectedPackage) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < 650L) return
        val root = rootInActiveWindow ?: return

        when (task) {
            "INSTAGRAM_REELS" -> openInstagramReels(root, prefs, now)
            "YOUTUBE_DOWNLOADS", "YOUTUBE_DOWNLOAD_INDEX" -> navigateYouTube(root, prefs, now, task)
        }
    }

    private fun openInstagramReels(
        root: AccessibilityNodeInfo,
        prefs: android.content.SharedPreferences,
        now: Long
    ) {
        val reels = findNode(root, setOf("reels", "reel"))
        if (reels != null && clickNodeOrParent(reels)) {
            prefs.edit().clear().apply()
            lastActionAt = now
            Toast.makeText(this, "THOR opened Instagram Reels. Playback depends on Instagram loading.", Toast.LENGTH_LONG).show()
        }
    }

    private fun navigateYouTube(
        root: AccessibilityNodeInfo,
        prefs: android.content.SharedPreferences,
        now: Long,
        task: String
    ) {
        val stage = prefs.getString("stage", "").orEmpty()
        if (stage == "WAIT_DOWNLOADS") {
            val downloads = findNode(root, setOf("downloads"))
            if (downloads != null && clickNodeOrParent(downloads)) {
                lastActionAt = now
                val target = prefs.getInt("item_number", 0)
                if (task == "YOUTUBE_DOWNLOADS" || target <= 0) {
                    prefs.edit().clear().apply()
                    Toast.makeText(this, "THOR opened YouTube Downloads.", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString("stage", "WAIT_VIDEO").putString("seen_items", "").apply()
                    Toast.makeText(this, "THOR opened Downloads. Finding video $target…", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // In current YouTube layouts, Downloads may be inside the You tab.
            val youTab = findNode(root, setOf("you", "library"))
            if (youTab != null && clickNodeOrParent(youTab)) {
                lastActionAt = now
            }
            return
        }

        if (stage == "WAIT_VIDEO") {
            val target = prefs.getInt("item_number", 0).coerceIn(1, 20)
            val visibleRows = collectVideoRows(root)
            if (visibleRows.isEmpty()) return

            val seen = prefs.getString("seen_items", "").orEmpty()
                .split("\n").filter { it.isNotBlank() }.toMutableList()
            visibleRows.forEach { row ->
                if (seen.none { it.equals(row.first, ignoreCase = true) }) seen.add(row.first)
            }
            prefs.edit().putString("seen_items", seen.joinToString("\n")).apply()

            if (seen.size >= target) {
                val targetLabel = seen[target - 1]
                val targetRow = visibleRows.firstOrNull { it.first.equals(targetLabel, ignoreCase = true) }
                if (targetRow != null && clickNodeOrParent(targetRow.second)) {
                    prefs.edit().clear().apply()
                    lastActionAt = now
                    Toast.makeText(this, "THOR selected Downloads item $target. Check that the intended video opened.", Toast.LENGTH_LONG).show()
                    return
                }
            }

            if (scrollDownloads(root)) lastActionAt = now
        }
    }

    private fun findNode(root: AccessibilityNodeInfo, labels: Set<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val label = (node.text?.toString() ?: node.contentDescription?.toString())
                .orEmpty().trim().lowercase(Locale.ROOT)
            val baseLabel = label.substringBefore(",").substringBefore(" •").trim()
            if (baseLabel in labels || labels.any { label.startsWith("$it ") }) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
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
                val label = (node.contentDescription?.toString() ?: node.text?.toString())
                    .orEmpty().replace(Regex("\\s+"), " ").trim()
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

    private fun scrollDownloads(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    override fun onInterrupt() = Unit
}
