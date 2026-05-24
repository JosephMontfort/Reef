package dev.pranav.reef.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.pranav.reef.R
import dev.pranav.reef.util.*
import dev.pranav.reef.util.NotificationHelper.BLOCKER_GROUP_KEY
import dev.pranav.reef.util.NotificationHelper.createNotificationChannel
import dev.pranav.reef.accessibility.FocusModeService

/**
 * Accessibility service retained ONLY for website / browser URL-bar blocking.
 *
 * App blocking (focus mode + daily/routine limits) has been moved to
 * [AppBlockerService] which uses UsageStatsManager polling instead and is
 * far more reliable on Xiaomi/MIUI devices.
 */
@SuppressLint("AccessibilityPolicy")
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var keyguardManager: KeyguardManager? = null
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }
    private var activeBrowserPackage: String? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> WebsiteUsageTracker.stopTracking()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    // Revive session on every screen-on — catches force-stop while screen was off
                    handler.postDelayed({
                        checkAndReviveSession()
                    }, 1_500L)
                }
            }
        }
    }

    private fun checkAndReviveSession() {
        if (!dev.pranav.reef.util.SessionPersistence.hasActiveSession(this)) return
        // Bug9/18: ActivityManager check always returns true because BlockerService is in the
        // same process. Use TimerStateManager.isRunning as the in-process liveness flag instead.
        val timerActive = dev.pranav.reef.timer.TimerStateManager.state.value.let {
            it.isRunning || it.isPaused
        }
        if (!timerActive) {
            try {
                startForegroundService(Intent(this, FocusModeService::class.java).apply {
                    action = FocusModeService.ACTION_RESUME_PERSISTED
                })
            } catch (e: Exception) { android.util.Log.e("BlockerService", "Revival failed", e) }
        }
    }

    private val websiteLimitPollRunnable = object : Runnable {
        override fun run() {
            try {
                val currentDomain = WebsiteUsageTracker.getCurrentTrackingDomain()
                if (currentDomain != null && WebsiteLimits.hasLimit(currentDomain)) {
                    val limit = WebsiteLimits.getLimit(currentDomain)
                    val usage = WebsiteUsageTracker.getDailyUsage(currentDomain)
                    if (usage >= limit) {
                        android.widget.Toast.makeText(this@BlockerService, "Reef Limit Reached: $currentDomain", android.widget.Toast.LENGTH_SHORT).show()
                        WebsiteUsageTracker.stopTracking()
                        
                        val isCustomBrowser = prefs.getStringSet("custom_browsers", emptySet())?.any { activeBrowserPackage?.startsWith(it.split(";;")[0]) == true } == true
                        val config = activeBrowserPackage?.let { browserConfigs[it] }
                        
                        if (config != null || isCustomBrowser) {
                            performRedirect(config, isCustomBrowser)
                        } else {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                        }
                        showWebsiteBlockedNotification(currentDomain, isRoutineBlock = false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("BlockerService", "Website limit poll error", e)
            }
            handler.postDelayed(this, WEBSITE_POLL_INTERVAL_MS)
        }
    }

    private data class BrowserConfig(
        val urlBarId: String,
        val suggestionBoxId: String,
        val isSuggestionBoxEqualToGo: Boolean = false,
        val suggestionBoxChildIndex: Int = 0
    )

    private val defaultBrowserConfigs = mapOf(
        "com.android.chrome" to BrowserConfig("com.android.chrome:id/url_bar", "com.android.chrome:id/omnibox_suggestions_dropdown"),
        "com.brave.browser" to BrowserConfig("com.brave.browser:id/url_bar", "com.brave.browser:id/omnibox_suggestions_dropdown"),
        "org.mozilla.firefox" to BrowserConfig("org.mozilla.firefox:id/mozac_browser_toolbar_url_view", "org.mozilla.firefox:id/sfcnt"),
        "com.opera.browser" to BrowserConfig("com.opera.browser:id/url_field", "com.opera.browser:id/right_state_button", true),
        "com.microsoft.emmx" to BrowserConfig("com.microsoft.emmx:id/url_bar", "com.microsoft.emmx:id/omnibox_suggestions_dropdown"),
        "com.duckduckgo.mobile.android" to BrowserConfig("com.duckduckgo.mobile.android:id/omnibarTextInput", "com.duckduckgo.mobile.android:id/browserSuggestionsList"),
        "com.vivaldi.browser" to BrowserConfig("com.vivaldi.browser:id/url_bar", "com.vivaldi.browser:id/omnibox_suggestions_dropdown"),
        "com.kiwibrowser.browser" to BrowserConfig("com.kiwibrowser.browser:id/url_bar", "com.kiwibrowser.browser:id/omnibox_suggestions_dropdown"),
        "com.ecosia.android" to BrowserConfig("com.ecosia.android:id/url_bar", "com.ecosia.android:id/omnibox_suggestions_dropdown"),
        "org.torproject.torbrowser" to BrowserConfig("org.torproject.torbrowser:id/mozac_browser_toolbar_url_view", "org.torproject.torbrowser:id/sfcnt")
    )

    private var cachedBrowserConfigs: Map<String, BrowserConfig> = emptyMap()
    private var lastConfigUpdateTime = 0L

    private val browserConfigs: Map<String, BrowserConfig>
        get() {
            val now = System.currentTimeMillis()
            if (now - lastConfigUpdateTime > 5000) {
                val configs = defaultBrowserConfigs.toMutableMap()
                try {
                    val customSet = prefs.getStringSet("custom_browsers", emptySet()) ?: emptySet()
                    for (custom in customSet) {
                        val parts = custom.split(";;")
                        if (parts.size >= 3) {
                            configs[parts[0]] = BrowserConfig(
                                urlBarId = parts[1],
                                suggestionBoxId = parts[2],
                                isSuggestionBoxEqualToGo = parts.getOrNull(3)?.toBoolean() ?: false,
                                suggestionBoxChildIndex = parts.getOrNull(4)?.toIntOrNull() ?: 0
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load custom browsers", e)
                }
                cachedBrowserConfigs = configs
                lastConfigUpdateTime = now
            }
            return cachedBrowserConfigs
        }

    private val redirectUrl = "about:blank"

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager

        WebsiteUsageTracker.init(this)
        WebsiteLimits.init(this)

        if (!isPrefsInitialized) {
            val deviceContext = createDeviceProtectedStorageContext()
            prefs = deviceContext.getSharedPreferences("prefs", MODE_PRIVATE)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        handler.post(websiteLimitPollRunnable)

        // Accessibility services are auto-restarted by Android after force-stop.
        // Use TimerStateManager (in-process liveness) instead of runningAppProcesses
        // (which always finds the current process — Bug18 fix).
        handler.postDelayed({
            if (dev.pranav.reef.util.SessionPersistence.hasActiveSession(this)) {
                val timerActive = dev.pranav.reef.timer.TimerStateManager.state.value.let {
                    it.isRunning || it.isPaused
                }
                if (!timerActive) {
                    try {
                        startForegroundService(Intent(this, FocusModeService::class.java).apply {
                            action = FocusModeService.ACTION_RESUME_PERSISTED
                        })
                    } catch (e: Exception) {
                        android.util.Log.e("BlockerService", "Revival failed", e)
                    }
                }
            }
        }, 3_000L)
    }



    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (keyguardManager?.isKeyguardLocked == true) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        val customBrowsersRaw = prefs.getStringSet("custom_browsers", emptySet()) ?: emptySet()
        val customBrowsers = customBrowsersRaw.map { it.split(";;")[0] }.toSet()
        val isCustomBrowser = customBrowsers.contains(pkg)

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!browserConfigs.containsKey(pkg) && !isCustomBrowser) {
                WebsiteUsageTracker.stopTracking()
                activeBrowserPackage = null
            } else {
                activeBrowserPackage = pkg
            }
        }

        if (!browserConfigs.containsKey(pkg) && !isCustomBrowser) return

        val config = browserConfigs[pkg]
        val root = rootInActiveWindow ?: return
        
        val urlBarNode = if (isCustomBrowser) {
            findDynamicUrlBarNode(root)
        } else {
            config?.let { findUrlBarNode(root, it.urlBarId) }
        } ?: return

        val url = extractUrlFromNode(urlBarNode) ?: return

        Log.d(TAG, "Found url=$url")
        val domain = sanitizeUrl(url)
        
        val blockedDomain = WebsiteBlocklist.resolveDomain(domain)
        if (blockedDomain != null) {
            android.widget.Toast.makeText(this, "Reef Blocked: $blockedDomain", android.widget.Toast.LENGTH_SHORT).show()
            WebsiteUsageTracker.stopTracking()
            performRedirect(config, isCustomBrowser)
            showWebsiteBlockedNotification(blockedDomain, isRoutineBlock = false)
            return
        }

        val limitedDomain = WebsiteLimits.resolveDomain(domain)
        if (limitedDomain != null) {
            WebsiteUsageTracker.startTracking(limitedDomain)
            val limit = WebsiteLimits.getLimit(limitedDomain)
            val usage = WebsiteUsageTracker.getDailyUsage(limitedDomain)
            if (usage >= limit) {
                android.widget.Toast.makeText(this, "Reef Limit Reached: $limitedDomain", android.widget.Toast.LENGTH_SHORT).show()
                WebsiteUsageTracker.stopTracking()
                performRedirect(config, isCustomBrowser)
                showWebsiteBlockedNotification(limitedDomain, isRoutineBlock = false)
            }
        } else {
            WebsiteUsageTracker.stopTracking()
        }
    }

    private fun findUrlBarNode(root: AccessibilityNodeInfo, fullId: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByViewId(fullId)
        return if (!nodes.isNullOrEmpty()) nodes[0] else null
    }

    private fun extractUrlFromNode(node: AccessibilityNodeInfo): String? {
        // Skip only when the user is actively editing the URL bar (both focused AND editable).
        // Checking isFocused alone was too aggressive — Chrome and Brave keep the URL bar
        // "focused" even after navigation completes, preventing any URL from being extracted.
        if (node.isFocused && node.isEditable && (node.text == null || !node.text.contains('.'))) return null
        val text = node.text?.toString() ?: return null
        if (text.isBlank() || !text.contains('.') || text.contains(' ')) return null
        return text
    }

    private fun sanitizeUrl(url: String): String =
        url.lowercase()
            .replace("https://", "")
            .replace("http://", "")
            .replace("www.", "")
            .substringBefore('/')

    private fun performRedirect(config: BrowserConfig?, isCustomBrowser: Boolean) {
        val initialRoot = rootInActiveWindow
        val urlBar = if (isCustomBrowser) {
             initialRoot?.let { findDynamicUrlBarNode(it) }
        } else {
             initialRoot?.let { findUrlBarNode(it, config!!.urlBarId) }
        }
        
        if (urlBar == null) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        handler.postDelayed({
            val editRoot = rootInActiveWindow ?: return@postDelayed
            val editText = if (isCustomBrowser) findDynamicUrlBarNode(editRoot) else findUrlBarNode(editRoot, config!!.urlBarId)
            if (editText == null) return@postDelayed
            
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, redirectUrl)
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            
            handler.postDelayed({
                val finalRoot = rootInActiveWindow ?: return@postDelayed
                val finalBar = if (isCustomBrowser) findDynamicUrlBarNode(finalRoot) else findUrlBarNode(finalRoot, config!!.urlBarId)
                
                val imeEnterPerformed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    finalBar?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ?: false
                } else false
                
                if (!imeEnterPerformed && !isCustomBrowser && config != null) {
                    performGoAction(finalRoot, config)
                }
            }, 300)
        }, 300)
    }

    private fun findDynamicUrlBarNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = java.util.LinkedList<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            if (node.isEditable) {
                val id = node.viewIdResourceName?.lowercase() ?: ""
                // 1. Precise Heuristic: Node is editable and its ID suggests it's a URL/Search bar
                if (id.contains("url") || id.contains("address") || id.contains("search") || id.contains("omnibox") || id.contains("edit")) {
                    return node
                }
                // 2. Fallback Heuristic: Save the very first editable field we find just in case
                if (fallback == null) fallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallback
    }

    private fun performGoAction(root: AccessibilityNodeInfo, config: BrowserConfig) {
        val nodes = root.findAccessibilityNodeInfosByViewId(config.suggestionBoxId) ?: return
        val box = nodes.firstOrNull() ?: return
        if (config.isSuggestionBoxEqualToGo) {
            box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            box.getChild(config.suggestionBoxChildIndex)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    @SuppressLint("MissingPermission")
    private fun showWebsiteBlockedNotification(domain: String, isRoutineBlock: Boolean) {
        if (!notificationManager.areNotificationsEnabled()) return
        val contentText = if (isRoutineBlock) {
            getString(R.string.website_blocked_by_routine)
        } else {
            getString(R.string.website_reached_limit, domain)
        }
        val notification = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.website_blocked))
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(BLOCKER_GROUP_KEY)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(domain.hashCode(), notification)

        val summary = NotificationCompat.Builder(this, BLOCKER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setGroup(BLOCKER_GROUP_KEY)
            .setGroupSummary(true)
            .build()
        notificationManager.notify(NotificationHelper.BLOCKER_SUMMARY_ID, summary)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(websiteLimitPollRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "BlockerService"
        private const val WEBSITE_POLL_INTERVAL_MS = 5_000L
    }
}
