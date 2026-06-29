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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
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
            // Timer circle — tightly wrapped with a little breathing room
            FocusTimerDisplay(size = 230.dp)
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
                modifier = Modifier.weight(1f)
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
    val appIcon = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName)
        }.getOrNull()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App icon with a subtle tinted container
            if (appIcon != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                    modifier = Modifier.size(88.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = appIcon.toBitmap().asImageBitmap(),
                            contentDescription = appName,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
            } else {
                Icon(
                    Icons.Rounded.Block,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(24.dp))
            }

            Text(
                text = stringResource(R.string.app_blocked),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))
            val message = when (reason) {
                UsageTracker.BlockReason.ROUTINE_LIMIT ->
                    stringResource(R.string.blocked_by_routine, appName)
                UsageTracker.BlockReason.FOCUS_MODE ->
                    stringResource(R.string.blocked_by_focus_mode, appName)
                else ->
                    stringResource(R.string.reached_limit, appName)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.go_home))
            }
        }
    }
}

/** Linear arc with 1% initial visual offset so progress looks alive instantly at start. */
private fun linearOffsetFraction(rawFraction: Float): Float =
    (rawFraction * 0.99f + 0.01f).coerceIn(0f, 1f)

@Composable
fun FocusTimerDisplay(size: Dp = 220.dp) {
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
    val visualFraction = linearOffsetFraction(rawFraction)

    val sweepFraction by animateFloatAsState(visualFraction, tween(950), label = "sweep")
    val colorScheme = MaterialTheme.colorScheme
    val arcColor by animateColorAsState(
        when {
            rawFraction > 0.5f  -> colorScheme.primary
            rawFraction > 0.25f -> colorScheme.tertiary
            else                -> colorScheme.error
        },
        tween(1500), label = "urgency"
    )

    // Breathing glow — only when running, very subtle scale of glow alpha
    val isRunning = timerState.isRunning && !timerState.isPaused
    val breathAlpha by animateFloatAsState(
        targetValue = if (isRunning) 1f else 0f,
        animationSpec = if (isRunning) androidx.compose.animation.core.infiniteRepeatable(
            animation = tween(2000),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ) else tween(600),
        label = "breath"
    )

    val totalSeconds = (remaining / 1000L).coerceAtLeast(0L)
    val h = totalSeconds / 3600; val m = (totalSeconds % 3600) / 60; val s = totalSeconds % 60
    val timeText = if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s)
                  else String.format(java.util.Locale.ROOT, "%02d:%02d", m, s)

    val staticLabel = when {
        timerState.isPaused -> "Paused"
        timerState.pomodoroPhase == dev.pranav.reef.timer.PomodoroPhase.SHORT_BREAK -> "Short Break"
        timerState.pomodoroPhase == dev.pranav.reef.timer.PomodoroPhase.LONG_BREAK -> "Long Break"
        else -> "Focus Time"
    }

    val isPaused = timerState.isPaused

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        val density = LocalDensity.current

        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = this.size.width * 0.055f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val strokePx = 2.5.dp.toPx()

            if (sweepFraction > 0f) {
                // Breathing outer glow layer
                val breathingAlpha = 0.04f + breathAlpha * 0.09f
                drawArc(arcColor.copy(alpha = breathingAlpha), -90f, sweepFraction * 360f,
                    false, topLeft, arcSize, style = Stroke(16.dp.toPx(), cap = StrokeCap.Round))
                // Static inner glow
                drawArc(arcColor.copy(alpha = 0.14f), -90f, sweepFraction * 360f,
                    false, topLeft, arcSize, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
            }
            // Track
            drawArc(colorScheme.surfaceVariant, -90f, 360f, false, topLeft, arcSize,
                style = Stroke(strokePx, cap = StrokeCap.Round))
            // Arc
            if (sweepFraction > 0f) {
                drawArc(arcColor, -90f, sweepFraction * 360f, false, topLeft, arcSize,
                    style = Stroke(strokePx, cap = StrokeCap.Round))
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            // Static label — shows session type, not time
            Text(
                text = staticLabel,
                style = MaterialTheme.typography.labelMedium,
                color = arcColor.copy(alpha = 0.85f),
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            // Live timer with JetBrains Mono
            Text(
                text = timeText,
                fontSize = (size.value * 0.155f).sp,
                fontWeight = FontWeight.Bold,
                fontFamily = dev.pranav.reef.ui.JetBrainsMonoFamily,
                color = colorScheme.onBackground,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(10.dp))
            // Pause/resume button
            val btnScale by animateFloatAsState(
                if (isPaused) 1.15f else 1f,
                spring(dampingRatio = 0.4f, stiffness = 400f), label = "btn"
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).scale(btnScale).clip(CircleShape)
                    .background(colorScheme.primaryContainer.copy(alpha = 0.85f))
                    .clickable {
                        context.startService(Intent(context, FocusModeService::class.java).apply {
                            action = if (isPaused) FocusModeService.ACTION_RESUME
                                     else FocusModeService.ACTION_PAUSE
                        })
                    }
            ) {
                Crossfade(isPaused, label = "icon") { paused ->
                    Icon(
                        if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
