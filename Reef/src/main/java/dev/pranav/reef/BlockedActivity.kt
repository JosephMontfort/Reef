package dev.pranav.reef

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.pranav.reef.accessibility.UsageTracker
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.Whitelist
import dev.pranav.reef.util.applyDefaults
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BlockedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PKG = "blocked_pkg"
        const val EXTRA_BLOCK_REASON = "block_reason"
        const val ACTION_DISMISS = "dev.pranav.reef.DISMISS_BLOCKED"
        var isShowing = false
    }

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Set to true right before we intentionally launch a whitelisted app from the
     * overlay grid.  This prevents [onUserLeaveHint] from re-showing the overlay
     * (which it must do for every other leave-hint, e.g. the user pressing Home).
     */
    private var isLaunchingWhitelisted = false

    private val isHomeBlockMode
        get() = prefs.getBoolean("block_home_screen", false) &&
                prefs.getBoolean("focus_mode", false)

    private val dismissReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_DISMISS) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDefaults()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val blockedPkg = intent.getStringExtra(EXTRA_BLOCKED_PKG) ?: ""
        val reasonStr  = intent.getStringExtra(EXTRA_BLOCK_REASON) ?: ""
        val reason = runCatching { UsageTracker.BlockReason.valueOf(reasonStr) }
            .getOrDefault(UsageTracker.BlockReason.DAILY_LIMIT)

        if (isHomeBlockMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(dismissReceiver, IntentFilter(ACTION_DISMISS), RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(dismissReceiver, IntentFilter(ACTION_DISMISS))
            }
        } else {
            handler.postDelayed({ goHome() }, 1_500)
        }

        setContent {
            ReefTheme {
                if (isHomeBlockMode) {
                    HomeBlockScreen(
                        onLaunchApp = { launchIntent ->
                            // Flag must be set BEFORE startActivity so that
                            // onUserLeaveHint sees it and skips the relaunch.
                            isLaunchingWhitelisted = true
                            runCatching { startActivity(launchIntent) }
                        }
                    )
                } else {
                    BlockedScreen(
                        packageName = blockedPkg,
                        reason = reason,
                        onGoHome = ::goHome
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isShowing = true
    }

    override fun onStop() {
        super.onStop()
        isShowing = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isHomeBlockMode) {
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ goHome() }, 1_500)
        }
    }

    /**
     * Called when the activity is about to go to the background due to a
     * *user* action (Home button, Recents, etc.).
     *
     * In home-block mode we immediately re-show the overlay so the user never
     * actually sees the home screen / launcher.  The only exception is when the
     * user tapped a whitelisted app from our grid — [isLaunchingWhitelisted]
     * is true in that case and we let the chosen app come to front normally.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isHomeBlockMode && !isLaunchingWhitelisted) {
            // Post to next looper frame so the relaunch happens right after
            // Android finishes processing the home-press gesture.
            handler.post {
                if (!isFinishing) {
                    startActivity(
                        Intent(this, BlockedActivity::class.java).apply {
                            // singleTask launch mode ensures the existing instance
                            // is reused (onNewIntent called); NO_ANIMATION prevents
                            // a visible flash of the home screen.
                            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        }
                    )
                }
            }
        }
        // Always clear the flag after the leave-hint is processed.
        isLaunchingWhitelisted = false
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(dismissReceiver) }
        isShowing = false
    }

    @Deprecated("Overridden to prevent returning to blocked app")
    override fun onBackPressed() {
        if (!isHomeBlockMode) goHome()
        // In home-block mode: back does nothing — overlay is intentionally persistent
    }
}

data class AllowedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

@Composable
fun HomeBlockScreen(onLaunchApp: (Intent) -> Unit) {
    val context = LocalContext.current
    var allowedApps by remember { mutableStateOf<List<AllowedApp>>(emptyList()) }

    LaunchedEffect(Unit) {
        allowedApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps.getActivityList(null, Process.myUserHandle()).distinctBy { it.applicationInfo.packageName }
                .mapNotNull { info ->
                    val pkg = info.applicationInfo.packageName
                    if (!Whitelist.isWhitelisted(pkg) || pkg == context.packageName) return@mapNotNull null
                    runCatching {
                        val icon = info.applicationInfo.loadIcon(pm).toBitmap().asImageBitmap()
                        AllowedApp(pkg, info.label.toString(), icon)
                    }.getOrNull()
                }
                .sortedBy { it.label }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(40.dp))
            Text("⏱", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.focus_mode),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.home_block_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.allowed_apps_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(allowedApps, key = { it.packageName }) { app ->
                    AllowedAppItem(app = app) {
                        runCatching {
                            val launchIntent =
                                context.packageManager.getLaunchIntentForPackage(app.packageName)
                                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            if (launchIntent != null) onLaunchApp(launchIntent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AllowedAppItem(app: AllowedApp, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun BlockedScreen(
    packageName: String,
    reason: UsageTracker.BlockReason,
    onGoHome: () -> Unit
) {
    val context = LocalContext.current
    val appName = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault(packageName)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "⏱", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_blocked),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            val message = when (reason) {
                UsageTracker.BlockReason.ROUTINE_LIMIT ->
                    stringResource(R.string.blocked_by_routine, appName)
                else ->
                    stringResource(R.string.reached_limit, appName)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))
            Button(onClick = onGoHome) {
                Text(stringResource(R.string.go_home))
            }
        }
    }
}
