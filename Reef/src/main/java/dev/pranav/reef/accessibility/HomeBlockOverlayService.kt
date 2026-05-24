package dev.pranav.reef.accessibility

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pranav.reef.HomeBlockScreen
import dev.pranav.reef.R
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.FOCUS_MODE_CHANNEL_ID
import dev.pranav.reef.util.isPrefsInitialized
import dev.pranav.reef.util.prefs

/**
 * Foreground service that draws a persistent [TYPE_APPLICATION_OVERLAY] window
 * over all other apps during a home-block focus session.
 *
 * Unlike an Activity overlay, a WindowManager window is NOT part of the activity
 * task stack.  Pressing Home or swiping up merely moves the launcher *behind* the
 * window — the window itself stays visible and intercepting touches on the nav bar
 * (button-nav) or covering it visually (gesture-nav).  Back gestures have no
 * concept in a Window, so they cannot dismiss it.
 */
@SuppressLint("MissingPermission")
class HomeBlockOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    // ── Compose lifecycle wiring for ComposeView inside a Service ──────────────
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ── Window ─────────────────────────────────────────────────────────────────
    private lateinit var wm: WindowManager
    private var overlayView: ComposeView? = null

    // ── Dismiss receiver ───────────────────────────────────────────────────────
    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS) stopSelf()
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        promoteToForeground() // Satisfy Android 12+ 5-second deadline instantly

        // Restore saved state before any Compose work
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (!isPrefsInitialized) {
            prefs = createDeviceProtectedStorageContext()
                .getSharedPreferences("prefs", MODE_PRIVATE)
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, IntentFilter(ACTION_DISMISS), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dismissReceiver, IntentFilter(ACTION_DISMISS))
        }

        addOverlayWindow()
        isShowing = true
    }

    override fun onDestroy() {
        // Tear down lifecycle before removing the view so Compose disposes cleanly
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        removeOverlayWindow()
        isShowing = false

        runCatching { unregisterReceiver(dismissReceiver) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Window management ──────────────────────────────────────────────────────

    private fun addOverlayWindow() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // FLAG_NOT_FOCUSABLE  — we keep it so the IME / system bars stay
            //                       functional.  Touch events are still delivered
            //                       to our window (only key events and focus are
            //                       excluded).
            // FLAG_LAYOUT_IN_SCREEN — allow window to extend into system bar areas
            // FLAG_LAYOUT_NO_LIMITS — remove the frame-size constraint so the
            //                         window can cover the nav bar region, making
            //                         nav buttons unresponsive (button-nav) or
            //                         visually covered (gesture-nav).
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = ComposeView(this).apply {
            // Wire up the Compose lifecycle to this Service's LifecycleOwner /
            // SavedStateRegistryOwner so remembered state and LaunchedEffects work.
            setViewTreeLifecycleOwner(this@HomeBlockOverlayService)
            setViewTreeSavedStateRegistryOwner(this@HomeBlockOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)

            setContent {
                ReefTheme {
                    HomeBlockScreen(
                        onLaunchApp = { launchIntent ->
                            // Remove overlay FIRST so the chosen app launches cleanly
                            removeOverlayWindow()
                            isShowing = false
                            runCatching { startActivity(launchIntent) }
                            stopSelf()
                        }
                    )
                }
            }
        }

        wm.addView(view, params)
        overlayView = view
    }

    private fun removeOverlayWindow() {
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
    }

    // ── Foreground notification ────────────────────────────────────────────────

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, FOCUS_MODE_CHANNEL_ID)
            .setSmallIcon(R.drawable.hourglass)
            .setContentTitle(getString(R.string.focus_mode))
            .setContentText(getString(R.string.home_block_subtitle))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "HomeBlockOverlayService"
        private const val NOTIF_ID = 9003

        /** True while the overlay window is attached to WindowManager. */
        var isShowing = false

        /** Send this broadcast to dismiss the overlay (e.g. when focus ends). */
        const val ACTION_DISMISS = "dev.pranav.reef.DISMISS_HOME_OVERLAY"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                android.util.Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay suppressed")
                return
            }
            // CRITICAL FIX: Because we have SYSTEM_ALERT_WINDOW, we are legally exempt from
            // background service restrictions. By using startService() instead of startForegroundService()
            // here, we bypass the OS's fatal 5-second crash timer entirely!
            try {
                context.startService(Intent(context, HomeBlockOverlayService::class.java))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "startService failed, falling back", e)
                try {
                    context.startForegroundService(Intent(context, HomeBlockOverlayService::class.java))
                } catch (e2: Exception) {
                    android.util.Log.e(TAG, "All start methods failed", e2)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HomeBlockOverlayService::class.java))
        }
    }
}
