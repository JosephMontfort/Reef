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
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                WebsiteUsageTracker.stopTracking()
            }
        }
    }

    private val websiteLimitPollRunnable = object : Runnable {
        override fun run() {
            try {
                val currentDomain = WebsiteUsageTracker.getCurrentTrackingDomain()
                if (currentDomain != null && WebsiteLimits.hasLimit(currentDomain)) {
                    val limit = WebsiteLimits.getLimit(currentDomain)
                    val usage = WebsiteUsageTracker.getDailyUsage(currentDomain)
                    Log.d(TAG, "limit=$limit, usage=$usage for $currentDomain")
                    if (usage >= limit) {
                        WebsiteUsageTracker.stopTracking()
                        val config = activeBrowserPackage?.let { browserConfigs[it] }
                        if (config != null) {
                            performRedirect(config)
                            showWebsiteBlockedNotification(currentDomain, isRoutineBlock = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Website limit poll error", e)
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

    private val browserConfigs = mapOf(
        "com.android.chrome" to BrowserConfig(
            urlBarId = "com.android.chrome:id/url_bar",
            suggestionBoxId = "com.android.chrome:id/omnibox_suggestions_dropdown"
        ),
        "com.brave.browser" to BrowserConfig(
            urlBarId = "com.brave.browser:id/url_bar",
            suggestionBoxId = "com.brave.browser:id/omnibox_suggestions_dropdown"
        ),
        "org.mozilla.firefox" to BrowserConfig(
            urlBarId = "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            suggestionBoxId = "org.mozilla.firefox:id/sfcnt"
        ),
        "com.opera.browser" to BrowserConfig(
            urlBarId = "com.opera.browser:id/url_field",
            suggestionBoxId = "com.opera.browser:id/right_state_button",
            isSuggestionBoxEqualToGo = true
        )
    )

    private val redirectUrl = "about:blank"

    override fun onServiceConnected() {
        super.onServiceConnected()
        configureService()
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
    }

    private fun configureService() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (keyguardManager?.isKeyguardLocked == true) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return

        // Track which browser is currently active
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!browserConfigs.containsKey(pkg)) {
                WebsiteUsageTracker.stopTracking()
                activeBrowserPackage = null
            } else {
                activeBrowserPackage = pkg
            }
        }

        // Only act on browser packages — app blocking is handled by AppBlockerService
        val config = browserConfigs[pkg] ?: return
        val root = rootInActiveWindow ?: event.source ?: return
        val urlBarNode = findUrlBarNode(root, config.urlBarId) ?: return
        val url = extractUrlFromNode(urlBarNode) ?: run {
            WebsiteUsageTracker.stopTracking()
            return
        }

        Log.d(TAG, "Found url=$url")
        val domain = sanitizeUrl(url)

        if (WebsiteBlocklist.isBlocked(domain)) {
            WebsiteUsageTracker.stopTracking()
            performRedirect(config)
            showWebsiteBlockedNotification(domain, isRoutineBlock = false)
            return
        }

        if (WebsiteLimits.hasLimit(domain)) {
            WebsiteUsageTracker.startTracking(domain)
            val limit = WebsiteLimits.getLimit(domain)
            val usage = WebsiteUsageTracker.getDailyUsage(domain)
            if (usage >= limit) {
                WebsiteUsageTracker.stopTracking()
                performRedirect(config)
                showWebsiteBlockedNotification(domain, isRoutineBlock = false)
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
        if (node.isFocused) return null
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

    private fun performRedirect(config: BrowserConfig) {
        val initialRoot = rootInActiveWindow ?: return
        val urlBar = findUrlBarNode(initialRoot, config.urlBarId) ?: return
        urlBar.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        handler.postDelayed({
            val editRoot = rootInActiveWindow ?: return@postDelayed
            val editText = findUrlBarNode(editRoot, config.urlBarId) ?: return@postDelayed
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    redirectUrl
                )
            }
            editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            handler.postDelayed({
                val finalRoot = rootInActiveWindow ?: return@postDelayed
                performGoAction(finalRoot, config)
            }, 300)
        }, 300)
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
