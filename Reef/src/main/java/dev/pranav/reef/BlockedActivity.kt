package dev.pranav.reef

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pranav.reef.accessibility.UsageTracker
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.WhitelistAppCache
import dev.pranav.reef.util.applyDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class BlockedActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BLOCKED_PKG = "blocked_pkg"
        const val EXTRA_BLOCK_REASON = "block_reason"
        var isShowing = false
    }

    private val handler = Handler(Looper.getMainLooper())

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

    override fun onResume() { super.onResume(); isShowing = true }
    override fun onStop()   { super.onStop();   isShowing = false }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ goHome() }, 1_500)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        isShowing = false
    }

    @Deprecated("Overridden to prevent returning to blocked app")
    override fun onBackPressed() { goHome() }
}

data class AllowedApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

@Composable
fun HomeBlockScreen(onLaunchApp: (Intent) -> Unit) {
    val context = LocalContext.current

    // Use pre-warmed cache — falls back to loading if cache is empty
    var allowedApps by remember {
        mutableStateOf(
            WhitelistAppCache.apps.map {
                AllowedApp(it.packageName, it.label, it.icon)
            }
        )
    }

    // If cache was empty (first open), load now and also warm for next time
    LaunchedEffect(Unit) {
        if (allowedApps.isEmpty()) {
            allowedApps = withContext(Dispatchers.IO) {
                WhitelistAppCache.refreshSync(context)
                WhitelistAppCache.apps.map { AllowedApp(it.packageName, it.label, it.icon) }
            }
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
            Spacer(Modifier.height(24.dp))

            // Synced focus timer
            FocusTimerDisplay(size = 160.dp)

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.home_block_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
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
                .systemBarsPadding()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Live synced timer — perfectly in sync with in-app timer
            FocusTimerDisplay(size = 200.dp)

            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.app_blocked),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
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

/**
 * Circular countdown ring with a live HH:MM:SS digital readout,
 * fed directly from [TimerStateManager.state] — zero broadcast lag,
 * perfectly synced with the in-app timer.
 *
 * The ring sweeps from full (all time remaining) to empty (0).
 * When the timer is paused the ring pulses slightly to signal the pause.
 */
@Composable
fun FocusTimerDisplay(size: Dp = 180.dp) {
    val timerState by TimerStateManager.state.collectAsState()

    // Derive a "session total" from the first non-zero snapshot so the ring
    // knows where 100% is.  We cache it in a remembered ref so it persists
    // across recompositions.
    val sessionTotal = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(timerState.isRunning) {
        if (timerState.isRunning && sessionTotal.longValue == 0L) {
            sessionTotal.longValue = timerState.timeRemaining
        }
    }
    // Reset when a new session starts (timeRemaining jumps up)
    LaunchedEffect(timerState.timeRemaining) {
        if (timerState.timeRemaining > sessionTotal.longValue) {
            sessionTotal.longValue = timerState.timeRemaining
        }
    }

    val total = sessionTotal.longValue.coerceAtLeast(1L)
    val remaining = timerState.timeRemaining.coerceIn(0L, total)
    val rawFraction = remaining.toFloat() / total.toFloat()

    // Animate the sweep so it moves smoothly between 1-second ticks
    val sweepFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = 950),
        label = "timerSweep"
    )

    val colorScheme = MaterialTheme.colorScheme
    val primaryColor   = colorScheme.primary
    val trackColor     = colorScheme.surfaceVariant
    val textColor      = colorScheme.onBackground
    val subTextColor   = colorScheme.onSurfaceVariant

    // Format HH:MM:SS
    val totalSeconds = (remaining / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    val timeText = if (h > 0)
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else
        String.format(Locale.ROOT, "%02d:%02d", m, s)

    val statusLabel = when {
        !timerState.isRunning && !timerState.isPaused -> "Not started"
        timerState.isPaused -> "Paused"
        else -> stringResource(R.string.focus_mode)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size)
        ) {
            val strokeWidth = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.07f).toPx() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = strokeWidth / 2f
                val arcSize = Size(this.size.width - strokeWidth, this.size.height - strokeWidth)
                val topLeft = Offset(inset, inset)

                // Track (background ring)
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Progress arc
                if (sweepFraction > 0f) {
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = sweepFraction * 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }
            }

            // Digital readout inside the ring
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = timeText,
                    fontSize = (size.value * 0.18f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelMedium,
            color = subTextColor
        )
    }
}
