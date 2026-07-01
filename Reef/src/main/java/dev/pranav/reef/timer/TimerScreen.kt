package dev.pranav.reef.timer

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.twotone.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import dev.pranav.reef.R
import dev.pranav.reef.navigation.Screen
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import dev.pranav.reef.ui.Typography
import dev.pranav.reef.ui.Typography.DMSerif
import dev.pranav.reef.util.formatTime
import dev.pranav.reef.util.WatchdogManager
import dev.pranav.reef.util.WhitelistAppCache
import dev.pranav.reef.util.prefs

sealed interface TimerConfig {
    data class Simple(val minutes: Int, val strictMode: Boolean): TimerConfig
    data class Pomodoro(
        val focusMinutes: Int,
        val shortBreakMinutes: Int,
        val longBreakMinutes: Int,
        val cycles: Int,
        val strictMode: Boolean
    ): TimerConfig
    data class CountUp(val ratio: Int, val strictMode: Boolean): TimerConfig
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerContent(
    navController: NavController,
    isTimerRunning: Boolean,
    isPaused: Boolean,
    currentTimeLeft: String,
    currentTimerState: String,
    isStrictMode: Boolean,
    onStartTimer: (TimerConfig) -> Unit,
    onPauseTimer: () -> Unit,
    onResumeTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onSkipTimer: () -> Unit,
    onRestartTimer: () -> Unit,
    onTakeBreak: () -> Unit = {}
) {
    val showRunningView = isTimerRunning || isPaused
    var selectedMode by remember { mutableIntStateOf(0) }

    // Congrats screen: shown when timer naturally completes (not cancelled)
    // Only if session ran for more than 60 seconds
    var showCongrats by remember { mutableStateOf(false) }
    var wasRunning by remember { mutableStateOf(false) }
    val sessionStartEpoch = remember { prefs.getLong("session_start_epoch", 0L) }

    LaunchedEffect(isTimerRunning, isPaused) {
        val justCompleted = wasRunning && !isTimerRunning && !isPaused
        if (justCompleted) {
            val sessionDurationMs = System.currentTimeMillis() - sessionStartEpoch
            if (sessionDurationMs >= 60_000L) {
                showCongrats = true
            }
        }
        wasRunning = isTimerRunning
    }

    if (showCongrats) {
        CongratsScreen(onDismiss = { showCongrats = false })
        return
    }

    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            Column(modifier = Modifier.animateContentSize()) {
                MediumTopAppBar(
                    title = {
                        Text(stringResource(R.string.focus_mode_title))
                    },
                    actions = {
                        IconButton(onClick = { navController.navigate(Screen.FocusStats) }) {
                            Icon(
                                Icons.Outlined.BarChart,
                                contentDescription = "Focus Stats"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    scrollBehavior = scrollBehavior
                )

                if (!showRunningView) {
                    FocusModeGroup(
                        selectedMode = selectedMode,
                        onSelectionChange = { selectedMode = it }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            AnimatedContent(targetState = showRunningView) { running ->
                if (running) {
                    RunningTimerView(
                        timeLeft = currentTimeLeft,
                        timerState = currentTimerState,
                        isPaused = isPaused,
                        isStrictMode = isStrictMode,
                        onPause = onPauseTimer,
                        onResume = onResumeTimer,
                        onCancel = onCancelTimer,
                        onSkip = onSkipTimer,
                        onRestart = onRestartTimer,
                        onTakeBreak = onTakeBreak
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                    ) {
                        Spacer(modifier = Modifier.height(16.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (selectedMode == 0) {
                                SimpleFocusSetup(onStartTimer)
                            } else {
                                PomodoroFocusSetup(onStartTimer)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TimerScreen(
    isTimerRunning: Boolean,
    isPaused: Boolean,
    currentTimeLeft: String,
    currentTimerState: String,
    isStrictMode: Boolean,
    onStartTimer: (TimerConfig) -> Unit,
    onPauseTimer: () -> Unit,
    onResumeTimer: () -> Unit,
    onCancelTimer: () -> Unit,
    onSkipTimer: () -> Unit = {},
    onRestartTimer: () -> Unit,
    onTakeBreak: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TimerContent(
            navController = rememberNavController(),
            isTimerRunning = isTimerRunning,
            isPaused = isPaused,
            currentTimeLeft = currentTimeLeft,
            currentTimerState = currentTimerState,
            isStrictMode = isStrictMode,
            onStartTimer = onStartTimer,
            onPauseTimer = onPauseTimer,
            onResumeTimer = onResumeTimer,
            onCancelTimer = onCancelTimer,
            onSkipTimer = onSkipTimer,
            onRestartTimer = onRestartTimer,
            onTakeBreak = onTakeBreak
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FocusModeGroup(
    selectedMode: Int,
    onSelectionChange: (Int) -> Unit
) {
    val modes = listOf(stringResource(R.string.timer_tab), stringResource(R.string.pomodoro_tab))

    FlowRow(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        modes.forEachIndexed { index, label ->
            ToggleButton(
                checked = index == selectedMode,
                onCheckedChange = {
                    if (selectedMode != index) {
                        onSelectionChange(index)
                    }
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    modes.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier
                    .weight(1f)
                    .semantics { role = Role.RadioButton },
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(imageVector = icon, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SimpleFocusSetup(onStart: (TimerConfig) -> Unit) {
    var isCountUp by remember { mutableStateOf(prefs.getBoolean("timer_is_count_up", false)) }
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }
    var ratio by remember { mutableIntStateOf(prefs.getInt("timer_count_up_ratio", 5)) }
    var isStrictMode by remember { mutableStateOf(false) }
    var blockHomeScreen by remember { mutableStateOf(prefs.getBoolean("block_home_screen", false)) }
    var nuclearWatchdog by remember { mutableStateOf(prefs.getBoolean("nuclear_watchdog_enabled", false)) }
    var resilienceMode by remember { mutableStateOf(prefs.getBoolean("resilience_mode_enabled", false)) }
    var preventStop by remember { mutableStateOf(prefs.getBoolean("prevent_stop_session", false)) }
    val context = LocalContext.current

    val totalMinutes = hours * 60 + minutes

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !isCountUp,
                onClick = {
                    isCountUp = false
                    prefs.edit().putBoolean("timer_is_count_up", false).apply()
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
            ) {
                Text(stringResource(R.string.countdown_mode_label))
            }
            SegmentedButton(
                selected = isCountUp,
                onClick = {
                    isCountUp = true
                    isStrictMode = false
                    prefs.edit().putBoolean("timer_is_count_up", true).apply()
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
            ) {
                Text(stringResource(R.string.count_up_mode_label))
            }
        }

        Spacer(Modifier.height(24.dp))

        if (!isCountUp) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (hours < 23) hours++ },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.extraLargeSquareShape,
                            pressedShape = IconButtonDefaults.largePressedShape
                        )
                    ) { Icon(Icons.Rounded.Add, stringResource(R.string.increase_hours)) }
                    Spacer(Modifier.width(120.dp))
                    FilledTonalIconButton(
                        onClick = {
                            if (minutes < 59) minutes++ else if (hours < 23) { hours++; minutes = 0 }
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.extraLargeSquareShape,
                            pressedShape = IconButtonDefaults.largePressedShape
                        )
                    ) { Icon(Icons.Rounded.Add, stringResource(R.string.increase_minutes)) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = hours.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 92.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 92.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Text(
                        text = minutes.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 92.sp, fontWeight = FontWeight.Bold)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (hours > 0) hours-- },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.extraLargeSquareShape,
                            pressedShape = IconButtonDefaults.largePressedShape
                        )
                    ) { Icon(Icons.Rounded.Remove, stringResource(R.string.decrease_hours)) }
                    Spacer(Modifier.width(120.dp))
                    FilledTonalIconButton(
                        onClick = {
                            if (minutes > 0) minutes-- else if (hours > 0) { hours--; minutes = 59 }
                        },
                        modifier = Modifier.size(64.dp),
                        shapes = IconButtonDefaults.shapes(
                            shape = IconButtonDefaults.extraLargeSquareShape,
                            pressedShape = IconButtonDefaults.largePressedShape
                        )
                    ) { Icon(Icons.Rounded.Remove, stringResource(R.string.decrease_minutes)) }
                }

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(0.8f),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    Text(stringResource(R.string.hours), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.minutes), style = MaterialTheme.typography.titleMedium)
                }
            }
        } else {
            // Count-Up mode UI
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.count_up_ratio_label),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.count_up_ratio_description, ratio),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(32.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FilledTonalIconButton(onClick = {
                        if (ratio > 1) { ratio--; prefs.edit().putInt("timer_count_up_ratio", ratio).apply() }
                    }) { Icon(Icons.Rounded.Remove, stringResource(R.string.decrease)) }
                    Text(
                        text = stringResource(R.string.ratio_format, ratio),
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = 72.sp, fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    FilledTonalIconButton(onClick = {
                        if (ratio < 10) { ratio++; prefs.edit().putInt("timer_count_up_ratio", ratio).apply() }
                    }) { Icon(Icons.Rounded.Add, stringResource(R.string.increase)) }
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ── Session toggles (all 5, strict mode hidden in count-up) ─────────
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (!isCountUp) {
                SettingsToggleRow(
                    icon = if (isStrictMode) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                    title = if (isStrictMode) stringResource(R.string.strict_mode) else stringResource(R.string.flexible_mode),
                    subtitle = if (isStrictMode) stringResource(R.string.no_pausing_allowed) else stringResource(R.string.pause_resume_anytime),
                    checked = isStrictMode,
                    onCheckedChange = { isStrictMode = it }
                )
            }
            SettingsToggleRow(
                icon = Icons.Rounded.PhoneLocked,
                title = stringResource(R.string.block_home_screen),
                subtitle = stringResource(R.string.block_home_screen_desc),
                checked = blockHomeScreen,
                onCheckedChange = { blockHomeScreen = it; prefs.edit().putBoolean("block_home_screen", it).apply() }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Block,
                title = stringResource(R.string.prevent_stop_session),
                subtitle = stringResource(R.string.prevent_stop_session_desc),
                checked = preventStop,
                onCheckedChange = { preventStop = it; prefs.edit().putBoolean("prevent_stop_session", it).apply() }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.resilience_mode),
                subtitle = stringResource(R.string.resilience_mode_desc),
                checked = resilienceMode,
                onCheckedChange = { resilienceMode = it; prefs.edit().putBoolean("resilience_mode_enabled", it).apply(); if (it && nuclearWatchdog) { nuclearWatchdog = false; prefs.edit().putBoolean("nuclear_watchdog_enabled", false).apply() } }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.nuclear_watchdog),
                subtitle = stringResource(R.string.nuclear_watchdog_desc),
                checked = nuclearWatchdog,
                onCheckedChange = { enabled ->
                    if (enabled && !WatchdogManager.hasRoot()) {
                        android.widget.Toast.makeText(context, context.getString(R.string.nuclear_watchdog_no_root), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        nuclearWatchdog = enabled
                        prefs.edit().putBoolean("nuclear_watchdog_enabled", enabled).apply()
                        if (enabled && resilienceMode) { resilienceMode = false; prefs.edit().putBoolean("resilience_mode_enabled", false).apply() }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (!isCountUp) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                AssistChip(onClick = { hours = 0; minutes = 5 }, label = { Text(pluralStringResource(R.plurals.minutes_label, 5, 5)) })
                AssistChip(onClick = { hours = 0; minutes = 15 }, label = { Text(pluralStringResource(R.plurals.minutes_label, 15, 15)) })
                AssistChip(onClick = { hours = 0; minutes = 30 }, label = { Text(pluralStringResource(R.plurals.minutes_label, 30, 30)) })
                AssistChip(onClick = { hours = 1; minutes = 0 }, label = { Text(pluralStringResource(R.plurals.hours_label, 1, 1)) })
                AssistChip(onClick = { hours = 2; minutes = 0 }, label = { Text(pluralStringResource(R.plurals.hours_label, 2, 2)) })
                AssistChip(onClick = { hours = 3; minutes = 0 }, label = { Text(pluralStringResource(R.plurals.hours_label, 3, 3)) })
                AssistChip(onClick = { hours = 1; minutes = 30 }, label = { Text(stringResource(R.string.hour_min_short_suffix, 1, 30, 30)) })
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = {
                if (isCountUp) onStart(TimerConfig.CountUp(ratio, isStrictMode))
                else onStart(TimerConfig.Simple(totalMinutes, isStrictMode))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = isCountUp || totalMinutes > 0,
            shapes = ButtonDefaults.shapes(pressedShape = ButtonDefaults.pressedShape),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isCountUp) stringResource(R.string.start_count_up) else stringResource(R.string.start),
                style = MaterialTheme.typography.titleLarge
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PomodoroFocusSetup(onStart: (TimerConfig) -> Unit) {
    var focusMinutes by remember { mutableIntStateOf(prefs.getInt("pomodoro_focus_minutes", 25)) }
    var shortBreakMinutes by remember { mutableIntStateOf(prefs.getInt("pomodoro_short_break_minutes", 5)) }
    var longBreakMinutes by remember { mutableIntStateOf(prefs.getInt("pomodoro_long_break_minutes", 15)) }
    var cycles by remember { mutableIntStateOf(prefs.getInt("pomodoro_cycles", 4)) }
    var isStrictMode by remember { mutableStateOf(false) }
    var blockHomeScreen by remember { mutableStateOf(prefs.getBoolean("block_home_screen", false)) }
    var nuclearWatchdog by remember { mutableStateOf(prefs.getBoolean("nuclear_watchdog_enabled", false)) }
    var resilienceMode by remember { mutableStateOf(prefs.getBoolean("resilience_mode_enabled", false)) }
    var preventStop by remember { mutableStateOf(prefs.getBoolean("prevent_stop_session", false)) }
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ExpressiveCounter(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.focus_label),
                    value = focusMinutes,
                    onValueChange = {
                        focusMinutes = it
                        // Persist immediately — changes from this screen are now durable
                        prefs.edit().putInt("pomodoro_focus_minutes", it).apply()
                    },
                    range = 1..120,
                    suffix = stringResource(R.string.min_short_suffix)
                )
                ExpressiveCounter(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.short_break_label),
                    value = shortBreakMinutes,
                    onValueChange = {
                        shortBreakMinutes = it
                        prefs.edit().putInt("pomodoro_short_break_minutes", it).apply()
                    },
                    range = 1..30,
                    suffix = stringResource(R.string.min_short_suffix)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ExpressiveCounter(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.long_break_label),
                    value = longBreakMinutes,
                    onValueChange = {
                        longBreakMinutes = it
                        prefs.edit().putInt("pomodoro_long_break_minutes", it).apply()
                    },
                    range = 1..60,
                    suffix = stringResource(R.string.min_short_suffix)
                )
                ExpressiveCounter(
                    modifier = Modifier.weight(1f),
                    label = stringResource(R.string.cycles_label),
                    value = cycles,
                    onValueChange = {
                        cycles = it
                        prefs.edit().putInt("pomodoro_cycles", it).apply()
                    },
                    range = 1..10,
                    suffix = ""
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ── Session toggles ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            SettingsToggleRow(
                icon = if (isStrictMode) Icons.Outlined.Lock else Icons.Rounded.LockOpen,
                title = if (isStrictMode) stringResource(R.string.strict_mode) else stringResource(R.string.flexible_mode),
                subtitle = if (isStrictMode) stringResource(R.string.no_pausing_allowed) else stringResource(R.string.pause_resume_anytime),
                checked = isStrictMode,
                onCheckedChange = { isStrictMode = it }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.PhoneLocked,
                title = stringResource(R.string.block_home_screen),
                subtitle = stringResource(R.string.block_home_screen_desc),
                checked = blockHomeScreen,
                onCheckedChange = { blockHomeScreen = it; prefs.edit().putBoolean("block_home_screen", it).apply() }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Block,
                title = stringResource(R.string.prevent_stop_session),
                subtitle = stringResource(R.string.prevent_stop_session_desc),
                checked = preventStop,
                onCheckedChange = { preventStop = it; prefs.edit().putBoolean("prevent_stop_session", it).apply() }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.resilience_mode),
                subtitle = stringResource(R.string.resilience_mode_desc),
                checked = resilienceMode,
                onCheckedChange = { resilienceMode = it; prefs.edit().putBoolean("resilience_mode_enabled", it).apply(); if (it && nuclearWatchdog) { nuclearWatchdog = false; prefs.edit().putBoolean("nuclear_watchdog_enabled", false).apply() } }
            )
            SettingsToggleRow(
                icon = Icons.Rounded.Security,
                title = stringResource(R.string.nuclear_watchdog),
                subtitle = stringResource(R.string.nuclear_watchdog_desc),
                checked = nuclearWatchdog,
                onCheckedChange = { enabled ->
                    if (enabled && !WatchdogManager.hasRoot()) {
                        android.widget.Toast.makeText(context, context.getString(R.string.nuclear_watchdog_no_root), android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        nuclearWatchdog = enabled
                        prefs.edit().putBoolean("nuclear_watchdog_enabled", enabled).apply()
                        if (enabled && resilienceMode) { resilienceMode = false; prefs.edit().putBoolean("resilience_mode_enabled", false).apply() }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                onStart(
                    TimerConfig.Pomodoro(
                        focusMinutes,
                        shortBreakMinutes,
                        longBreakMinutes,
                        cycles,
                        isStrictMode
                    )
                )
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Icon(
                imageVector = Icons.TwoTone.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.start_pomodoro),
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveCounter(
    modifier: Modifier = Modifier,
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    suffix: String
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )

        Text(
            text = "$value$suffix",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = { if (value > range.first) onValueChange(value - 1) },
                modifier = Modifier.size(40.dp),
                shapes = IconButtonDefaults.shapes()
            ) {
                Icon(Icons.Rounded.Remove, stringResource(R.string.decrease))
            }
            FilledTonalIconButton(
                onClick = { if (value < range.last) onValueChange(value + 1) },
                modifier = Modifier.size(40.dp),
                shapes = IconButtonDefaults.shapes()
            ) {
                Icon(Icons.Rounded.Add, stringResource(R.string.increase))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunningTimerView(
    timeLeft: String,
    timerState: String,
    isPaused: Boolean,
    isStrictMode: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit = {},
    onRestart: () -> Unit = {},
    onTakeBreak: () -> Unit = {}
) {
    val state by TimerStateManager.state.collectAsState()
    val isPomodoroMode = state.isPomodoroMode
    val isCountUpMode = state.isCountUpMode
    val currentCycle = state.currentCycle
    val totalCycles = state.totalCycles
    val isCountUpBreak = state.pomodoroPhase == PomodoroPhase.COUNT_UP_BREAK

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        // ── Push timer to vertical center of upper half (~25% from top) ──
        Spacer(modifier = Modifier.fillMaxHeight(0.12f))

            if (isPomodoroMode) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    AssistChip(
                        onClick = { },
                        label = {
                            Text(
                                text = stringResource(R.string.pomodoro_tab),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )

                    if (timerState == "FOCUS") {
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.cycle_count,
                                        currentCycle,
                                        totalCycles
                                    ),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            } else if (isCountUpMode) {
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(R.string.count_up_mode_label)) },
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Rounded.TrendingUp, null, Modifier.size(16.dp))
                    },
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }

            val stateText = when (timerState) {
                "FOCUS" -> stringResource(R.string.focus_label)
                "SHORT_BREAK" -> stringResource(R.string.short_break_label)
                "LONG_BREAK" -> stringResource(R.string.long_break_label)
                "COUNT_UP_BREAK" -> stringResource(R.string.short_break_label)
                else -> stringResource(R.string.focus_label)
            }

            val isBreak = timerState == "SHORT_BREAK" || timerState == "LONG_BREAK" || isCountUpBreak

            if (isBreak) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .padding(bottom = 8.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = stateText,
                style = MaterialTheme.typography.displaySmall,
                color = if (isBreak) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp),
                fontWeight = FontWeight.Bold,
                fontFamily = DMSerif,
            )

            BoxWithConstraints(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                val timeText = if (isCountUpMode && !isCountUpBreak) formatTime(state.focusTimeElapsed) else timeLeft
                // Scale down font when showing H:MM:SS (7 chars) vs MM:SS (5 chars)
                val baseFontSize = (maxWidth.value * 0.22f).coerceIn(48f, 88f)
                val scaledFontSize = when {
                    timeText.length >= 8 -> baseFontSize * 0.72f  // H:MM:SS
                    timeText.length >= 7 -> baseFontSize * 0.78f
                    else -> baseFontSize
                }.sp

                Text(
                    text = timeText,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    fontFamily = DMSerif,
                    fontSize = scaledFontSize,
                    color = if (isBreak) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface
                )
            }

            if (isCountUpMode && !isCountUpBreak) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Coffee, null, Modifier.size(18.dp))
                        Text(
                            text = stringResource(R.string.earned_break_budget, formatTime(state.breakBudget)),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (isStrictMode) {
                Text(
                    text = stringResource(R.string.strict_mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else if (isPaused) {
                Text(
                    text = stringResource(R.string.paused_status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            } else if (isBreak) {
                Text(
                    text = stringResource(R.string.take_a_break_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .padding(horizontal = 32.dp)
                )
            }

            // ── Bottom section: allowed apps strip — sits closer to buttons ──
            Spacer(modifier = Modifier.weight(1f))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                val allowedApps = WhitelistAppCache.apps
                if (allowedApps.isNotEmpty() && !isBreak) {
                    Text(
                        text = stringResource(R.string.allowed_apps_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Start)
                            .padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    val launchContext = LocalContext.current
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(allowedApps, key = { it.packageName }) { app ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable {
                                        launchContext.packageManager
                                            .getLaunchIntentForPackage(app.packageName)
                                            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                                            ?.let { launchContext.startActivity(it) }
                                    }
                                    .padding(4.dp)
                            ) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = app.label,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 56.dp)
                                )
                            }
                        }
                    }
                }
            }

        // ── Actions follow directly below allowed apps ───────────────────

        if (!isStrictMode || (isPaused && isBreak)) {
            RunningTimerActions(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth(),
                isPaused = isPaused,
                isBreak = isBreak,
                preventStop = prefs.getBoolean("prevent_stop_session", false),
                isCountUpMode = isCountUpMode,
                canRedeemBreak = isCountUpMode && !isCountUpBreak && state.breakBudget > 0,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onSkip = onSkip,
                onRestart = onRestart,
                onTakeBreak = onTakeBreak,
                isStrictMode = isStrictMode
            )
        } else {
            Text(
                text = stringResource(R.string.no_interruptions_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunningTimerActions(
    modifier: Modifier = Modifier,
    isPaused: Boolean,
    isStrictMode: Boolean,
    isBreak: Boolean = false,
    preventStop: Boolean = false,
    isCountUpMode: Boolean = false,
    canRedeemBreak: Boolean = false,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit = {},
    onRestart: () -> Unit = {},
    onTakeBreak: () -> Unit = {}
) {
    var showPreventStopDialog by remember { mutableStateOf(false) }

    // Grace countdown: only runs once right after a session starts.
    // We key it to the session's phaseEndEpoch so navigating away and back
    // doesn't restart it. Once elapsed it stays at 0 for the session lifetime.
    val sessionStartKey = remember { prefs.getLong("session_start_epoch", 0L) }
    val graceWindowMs = 5_000L
    val elapsedSinceStart = System.currentTimeMillis() - sessionStartKey
    var graceSecondsLeft by remember(sessionStartKey) {
        val remaining = ((graceWindowMs - elapsedSinceStart) / 1_000L).coerceIn(0L, 5L)
        mutableIntStateOf(if (preventStop && remaining > 0) remaining.toInt() else 0)
    }
    LaunchedEffect(sessionStartKey) {
        while (graceSecondsLeft > 0) {
            kotlinx.coroutines.delay(1_000)
            graceSecondsLeft = (graceSecondsLeft - 1).coerceAtLeast(0)
        }
    }
    val graceFraction by animateFloatAsState(
        targetValue = if (graceSecondsLeft > 0) graceSecondsLeft / 5f else 0f,
        animationSpec = tween(900),
        label = "graceFraction"
    )

    if (showPreventStopDialog) {
        AlertDialog(
            onDismissRequest = { showPreventStopDialog = false },
            icon = { Icon(Icons.Rounded.Block, contentDescription = null) },
            title = { Text(stringResource(R.string.prevent_stop_title)) },
            text = { Text(stringResource(R.string.prevent_stop_message)) },
            confirmButton = {
                TextButton(onClick = { showPreventStopDialog = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Pause/resume — hidden during breaks
            if (!isBreak) {
                IconToggleButton(
                    checked = isPaused,
                    onCheckedChange = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        if (isPaused) onResume() else onPause()
                    },
                    shapes = IconButtonDefaults.toggleableShapes(
                        shape = if (isPaused) IconButtonDefaults.largeSquareShape
                        else IconButtonDefaults.extraLargeSquareShape,
                    ),
                    colors = IconButtonDefaults.filledIconToggleButtonColors(
                        MaterialTheme.colorScheme.secondaryContainer,
                        checkedContainerColor = MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.height(62.dp).aspectRatio(0.89f)
                ) {
                    Icon(
                        modifier = Modifier.fillMaxSize().padding(9.dp),
                        imageVector = if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = if (isPaused) stringResource(R.string.resume)
                        else stringResource(R.string.pause)
                    )
                }
            }

            if (!isStrictMode) {
                if (canRedeemBreak) {
                    FilledTonalButton(
                        onClick = onTakeBreak,
                        modifier = Modifier.weight(1f).height(64.dp)
                    ) {
                        Text(text = stringResource(R.string.redeem_break), style = MaterialTheme.typography.titleMedium)
                    }
                }
                // Cancel / Stop Focusing button with optional grace countdown overlay
                Box(
                    modifier = Modifier.weight(1f).padding(12.dp).height(84.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val inGrace = graceSecondsLeft > 0
                    val buttonLabel = if (inGrace) stringResource(R.string.cancel)
                    else stringResource(R.string.stop_focusing)

                    Button(
                        onClick = {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            when {
                                inGrace -> onCancel()
                                preventStop && !isBreak -> showPreventStopDialog = true
                                isCountUpMode && preventStop && !isBreak -> showPreventStopDialog = true
                                else -> onCancel()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (inGrace && graceFraction > 0f) {
                                LinearProgressIndicator(
                                    progress = { graceFraction },
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clip(MaterialTheme.shapes.extraLarge),
                                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
                                    trackColor = androidx.compose.ui.graphics.Color.Transparent,
                                    strokeCap = StrokeCap.Round
                                )
                            }
                            Text(
                                text = if (inGrace) "${stringResource(R.string.cancel)} (${graceSecondsLeft}s)"
                                else stringResource(R.string.stop_focusing),
                                style = MaterialTheme.typography.titleLargeEmphasized
                            )
                        }
                    }
                }

                // Reset — only during focus phase
                if (!isBreak) {
                    OutlinedButton(
                        onClick = onRestart,
                        shapes = ButtonDefaults.shapes(shape = ButtonDefaults.elevatedShape),
                        modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            modifier = Modifier.fillMaxSize(),
                            imageVector = Icons.Filled.Replay,
                            contentDescription = stringResource(R.string.reset)
                        )
                    }
                }
            }
        }

        // Skip break button — shown during break phases
        if (isBreak) {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
                shapes = ButtonDefaults.shapes()
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.skip_break))
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun TimerScreenPreview() {
    MaterialTheme {
        TimerScreen(
            isTimerRunning = false,
            isPaused = false,
            currentTimeLeft = "25:00",
            currentTimerState = "FOCUS",
            isStrictMode = false,
            onStartTimer = {},
            onPauseTimer = {},
            onResumeTimer = {},
            onCancelTimer = {},
            onRestartTimer = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RunningTimerScreenPreview() {
    MaterialTheme {
        RunningTimerView(
            timeLeft = "12:34",
            timerState = "FOCUS",
            isPaused = false,
            isStrictMode = false,
            onPause = {},
            onResume = {},
            onCancel = {},
            onRestart = {}
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CongratsScreen(onDismiss: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    // Confetti particles
    data class Particle(
        val x: Float, val y: Float,
        val vx: Float, val vy: Float,
        val color: androidx.compose.ui.graphics.Color,
        val size: Float, val rotation: Float, val rotationSpeed: Float
    )

    val particleColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.secondaryContainer
    )

    val particles = remember {
        List(60) {
            Particle(
                x = (0.1f..0.9f).random(),
                y = (-0.2f..0.2f).random(),
                vx = (-0.003f..0.003f).random(),
                vy = (0.003f..0.008f).random(),
                color = particleColors.random(),
                size = (6f..14f).random(),
                rotation = (0f..360f).random(),
                rotationSpeed = (-5f..5f).random()
            )
        }
    }

    var particleStates by remember { mutableStateOf(particles) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        kotlinx.coroutines.delay(50)
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
        while (true) {
            kotlinx.coroutines.delay(16)
            tick++
            particleStates = particleStates.map { p ->
                p.copy(x = p.x + p.vx, y = p.y + p.vy, rotation = p.rotation + p.rotationSpeed)
            }
        }
    }

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "scale"
    )
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "alpha"
    )

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        // Draw confetti
        Canvas(modifier = Modifier.fillMaxSize()) {
            particleStates.forEach { p ->
                if (p.y < 1.1f) {
                    drawRect(
                        color = p.color.copy(alpha = (1f - p.y).coerceIn(0f, 0.9f)),
                        topLeft = androidx.compose.ui.geometry.Offset(
                            p.x * size.width,
                            p.y * size.height
                        ),
                        size = androidx.compose.ui.geometry.Size(p.size, p.size * 0.6f),
                    )
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.alpha(alpha).scale(scale).padding(32.dp)
        ) {
            Text(
                text = "🎉",
                fontSize = 72.sp
            )
            Text(
                text = stringResource(R.string.focus_session_complete),
                style = MaterialTheme.typography.headlineLargeEmphasized,
                fontFamily = Typography.DMSerif,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.focus_session_complete_message),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.done), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun ClosedFloatingPointRange<Float>.random(): Float =
    start + (endInclusive - start) * kotlin.random.Random.nextFloat()
