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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.accessibility.UsageTracker
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.WhitelistAppCache
import dev.pranav.reef.util.SessionPersistence
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
            Spacer(Modifier.height(20.dp))
            FocusTimerDisplay(size = 260.dp)
            Spacer(Modifier.height(28.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))
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
 * Non-linear arc: first 50% of elapsed time sweeps 70% of the arc (feels fast),
 * next 25% sweeps 15% (medium), final 25% sweeps 15% (slow) — creates urgency illusion.
 * Actual clock readout is always truthful.
 */
private fun nonLinearArcFraction(rawFraction: Float): Float {
    // rawFraction = remaining/total (1.0=full, 0.0=empty)
    // elapsed = 1 - rawFraction
    val elapsed = 1f - rawFraction.coerceIn(0f, 1f)
    val visual = when {
        elapsed <= 0.5f  -> elapsed * (0.70f / 0.50f)          // 0→0.5 maps to 0→0.70
        elapsed <= 0.75f -> 0.70f + (elapsed - 0.50f) * (0.15f / 0.25f) // 0.5→0.75 → 0.70→0.85
        else             -> 0.85f + (elapsed - 0.75f) * (0.15f / 0.25f) // 0.75→1.0 → 0.85→1.0
    }
    return (1f - visual).coerceIn(0f, 1f) // back to remaining fraction
}

@Composable
fun FocusTimerDisplay(size: Dp = 240.dp) {
    val timerState by TimerStateManager.state.collectAsState()
    val context = LocalContext.current

    val sessionTotal = remember {
        val persisted = SessionPersistence.restore(context)?.phaseDurationMs ?: 0L
        mutableLongStateOf(persisted.coerceAtLeast(timerState.timeRemaining))
    }
    LaunchedEffect(timerState.timeRemaining) {
        if (timerState.timeRemaining > sessionTotal.longValue + 5_000L)
            sessionTotal.longValue = timerState.timeRemaining
    }

    val total = sessionTotal.longValue.coerceAtLeast(1L)
    val remaining = timerState.timeRemaining.coerceIn(0L, total)
    val rawFraction = remaining.toFloat() / total.toFloat()
    val visualFraction = nonLinearArcFraction(rawFraction)

    val sweepFraction by animateFloatAsState(
        targetValue = visualFraction,
        animationSpec = tween(950),
        label = "timerSweep"
    )

    val colorScheme = MaterialTheme.colorScheme
    val arcColor by animateColorAsState(
        targetValue = when {
            rawFraction > 0.5f  -> colorScheme.primary
            rawFraction > 0.25f -> colorScheme.tertiary
            else                -> colorScheme.error
        },
        animationSpec = tween(1500),
        label = "arcUrgency"
    )

    val totalSeconds = (remaining / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600; val m = (totalSeconds % 3600) / 60; val s = totalSeconds % 60
    val timeText = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                  else String.format(Locale.ROOT, "%02d:%02d", m, s)

    val isPaused = timerState.isPaused

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
            val density = LocalDensity.current
            val strokePx = with(density) { (size * 0.065f).toPx() }
            val radiusPx = with(density) { (size / 2).toPx() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = strokePx / 2f
                val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
                val topLeft = Offset(inset, inset)
                val center = Offset(this.size.width / 2f, this.size.height / 2f)

                drawArc(
                    color = colorScheme.surfaceVariant, startAngle = -90f, sweepAngle = 360f,
                    useCenter = false, topLeft = topLeft, size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
                if (sweepFraction > 0f) {
                    drawArc(
                        color = arcColor, startAngle = -90f,
                        sweepAngle = sweepFraction * 360f,
                        useCenter = false, topLeft = topLeft, size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round)
                    )
                }
                // Clock ticks
                val tickOuterR = radiusPx - strokePx - with(density) { 6.dp.toPx() }
                for (i in 0 until 60) {
                    val isMajor = i % 5 == 0
                    val angle = Math.toRadians((i * 6 - 90).toDouble())
                    val tickLen = with(density) { if (isMajor) 10.dp.toPx() else 5.dp.toPx() }
                    val cos = kotlin.math.cos(angle).toFloat()
                    val sin = kotlin.math.sin(angle).toFloat()
                    drawLine(
                        color = if (isMajor) colorScheme.outline else colorScheme.outlineVariant,
                        start = Offset(center.x + cos * tickOuterR, center.y + sin * tickOuterR),
                        end = Offset(center.x + cos * (tickOuterR - tickLen), center.y + sin * (tickOuterR - tickLen)),
                        strokeWidth = with(density) { if (isMajor) 2.dp.toPx() else 1.dp.toPx() },
                        cap = StrokeCap.Round
                    )
                }
            }

            // Centre: time + play/pause button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = timeText,
                    fontSize = (size.value * 0.165f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onBackground,
                    letterSpacing = 1.5.sp
                )

                // YouTube-style animated play/pause
                val pauseScale by animateFloatAsState(
                    targetValue = if (isPaused) 1.15f else 1f,
                    animationSpec = spring(dampingRatio = 0.4f, stiffness = 400f),
                    label = "pauseScale"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .scale(pauseScale)
                        .clip(CircleShape)
                        .background(colorScheme.primaryContainer.copy(alpha = 0.85f))
                        .clickable {
                            context.startService(
                                Intent(context, FocusModeService::class.java).apply {
                                    action = if (isPaused) FocusModeService.ACTION_RESUME
                                             else FocusModeService.ACTION_PAUSE
                                }
                            )
                        }
                ) {
                    Crossfade(targetState = isPaused, label = "playPauseIcon") { paused ->
                        Icon(
                            imageVector = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            contentDescription = if (paused) "Resume" else "Pause",
                            tint = colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Time-remaining badge
        Surface(
            color = arcColor.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier
        ) {
            Text(
                text = when {
                    h > 0 -> "${h}h ${m}m remaining"
                    m > 0 -> "${m}m ${s}s remaining"
                    else  -> "${s}s remaining"
                },
                style = MaterialTheme.typography.labelMedium,
                color = arcColor,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}
