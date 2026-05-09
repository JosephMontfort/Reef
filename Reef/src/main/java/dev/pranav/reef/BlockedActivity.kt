package dev.pranav.reef

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.reef.accessibility.UsageTracker
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.applyDefaults

class BlockedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PKG = "blocked_pkg"
        const val EXTRA_BLOCK_REASON = "block_reason"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        applyDefaults()
        super.onCreate(savedInstanceState)

        // ── Window flags for reliable display on MIUI/HyperOS ──────────────────
        // Keep screen on and allow display over the lock screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            // FLAG_SHOW_WHEN_LOCKED is deprecated but kept for pre-27 MIUI which
            // ignores the Activity API above.
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val blockedPkg = intent.getStringExtra(EXTRA_BLOCKED_PKG) ?: ""
        val reasonStr  = intent.getStringExtra(EXTRA_BLOCK_REASON) ?: ""
        val reason = runCatching { UsageTracker.BlockReason.valueOf(reasonStr) }
            .getOrDefault(UsageTracker.BlockReason.DAILY_LIMIT)

        // Auto-send user home after showing the screen briefly.
        handler.postDelayed({ goHome() }, 1_500)

        setContent {
            ReefTheme {
                BlockedScreen(
                    packageName = blockedPkg,
                    reason = reason,
                    onGoHome = ::goHome
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        // singleTask: re-arm the auto-home timer if a new block arrives
        super.onNewIntent(intent)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ goHome() }, 1_500)
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
    }

    @Deprecated("Overridden to prevent returning to blocked app")
    override fun onBackPressed() = goHome()
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
