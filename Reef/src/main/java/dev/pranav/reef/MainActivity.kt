package dev.pranav.reef

import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.EmojiNature
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.pranav.reef.accessibility.FocusModeService
import dev.pranav.reef.intro.AppIntroActivity
import dev.pranav.reef.navigation.Screen
import dev.pranav.reef.screens.*
import dev.pranav.reef.timer.TimerConfig
import dev.pranav.reef.timer.TimerContent
import dev.pranav.reef.timer.TimerStateManager
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.ui.blocklist.WebsiteBlocklistScreen
import dev.pranav.reef.ui.focusstats.FocusSessionDetailScreen
import dev.pranav.reef.ui.focusstats.FocusStatsScreen
import dev.pranav.reef.util.*

class MainActivity: ComponentActivity() {
    private var pendingFocusModeStart = false
    private var hasCheckedPermissions = false
    private var shouldNavigateToTimer = false

    private val timerReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val left = intent.getStringExtra(FocusModeService.EXTRA_TIME_LEFT) ?: "00:00"
            currentTimeLeft = left
            currentTimerState = intent.getStringExtra(FocusModeService.EXTRA_TIMER_STATE) ?: "FOCUS"
            if (left == "00:00" && !prefs.getBoolean("pomodoro_mode", false)) {
                AndroidUtilities.vibrate(context, 500)
            }
        }
    }

    private val soundPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                result.data?.getParcelableExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                    android.net.Uri::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                result.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            }
            uri?.let { prefs.edit { putString("pomodoro_sound", it.toString()) } }
        }
    }

    private var currentTimeLeft by mutableStateOf("00:00")
    private var currentTimerState by mutableStateOf("FOCUS")

    private val usageStatsManager by lazy { getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager }
    private val launcherApps by lazy { getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyDefaults()
        addExceptions()

        shouldNavigateToTimer = intent?.getBooleanExtra("navigate_to_timer", false) == true
        val shouldNavigateToRoutines =
            intent?.getBooleanExtra("navigate_to_routines", false) == true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                timerReceiver,
                IntentFilter("dev.pranav.reef.TIMER_UPDATED"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(timerReceiver, IntentFilter("dev.pranav.reef.TIMER_UPDATED"))
        }

        setContent {
            val navController = rememberNavController()
            val timerState by TimerStateManager.state.collectAsState()
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination

            var whitelistedCount by remember {
                mutableIntStateOf(Whitelist.getWhitelistedLaunchableCount(launcherApps))
            }

            // Refresh count whenever the user returns from the whitelist screen
            LaunchedEffect(currentDestination) {
                if (currentDestination?.hasRoute<Screen.Whitelist>() == false) {
                    whitelistedCount = Whitelist.getWhitelistedLaunchableCount(launcherApps)
                }
            }

            val selectedNavIndex = remember(currentDestination) {
                when {
                    currentDestination?.hasRoute<Screen.Home>() == true -> 0
                    currentDestination?.hasRoute<Screen.Usage>() == true -> 1
                    currentDestination?.hasRoute<Screen.Timer>() == true -> 2
                    currentDestination?.hasRoute<Screen.Settings>() == true -> 3
                    else -> -1
                }
            }

            val showBottomBar = remember(currentDestination) {
                currentDestination?.hasRoute<Screen.Home>() == true ||
                        currentDestination?.hasRoute<Screen.Usage>() == true ||
                        currentDestination?.hasRoute<Screen.Timer>() == true ||
                        currentDestination?.hasRoute<Screen.Settings>() == true ||
                        currentDestination?.hasRoute<Screen.Whitelist>() == true ||
                        currentDestination?.hasRoute<Screen.Routines>() == true
            }

            LaunchedEffect(Unit) {
                if (timerState.isRunning || timerState.isPaused) {
                    currentTimeLeft = "00:00"
                    currentTimerState = timerState.pomodoroPhase.name
                }
            }

            LaunchedEffect(shouldNavigateToTimer, shouldNavigateToRoutines) {
                if (shouldNavigateToTimer) {
                    navController.navigate(Screen.Timer) { launchSingleTop = true }
                    this@MainActivity.shouldNavigateToTimer = false
                } else if (shouldNavigateToRoutines) {
                    navController.navigate(Screen.Routines) { launchSingleTop = true }
                }
            }

            var dailyUsageText by remember { mutableStateOf("0m today") }

                        LaunchedEffect(Unit) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val usageText = try {
                        val todayUsage = ScreenUsageHelper.fetchAppUsageTodayTillNow(usageStatsManager)
                        
                        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
                        val defaultLauncher = packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
                        val exclusions = listOfNotNull("com.android.systemui", defaultLauncher)
                        
                        val totalUsageMinutes = todayUsage.filterKeys { it !in exclusions }.values.sum() / 60
                        
                        val hours = totalUsageMinutes / 60
                        val minutes = totalUsageMinutes % 60
                        when {
                            hours > 0 && minutes > 0 -> getString(R.string.hour_min_short_suffix, hours, minutes) + " " + getString(R.string.today)
                            hours > 0 -> getString(R.string.hours_short_format, hours) + " " + getString(R.string.today)
                            minutes > 0 -> getString(R.string.minutes_short_format, minutes) + " " + getString(R.string.today)
                            else -> getString(R.string.less_than_one_minute)
                        }
                    } catch (e: Exception) {
                        "0m today"
                    }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        dailyUsageText = usageText
                    }
                }
            }


            ReefTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    bottomBar = {
                        AnimatedVisibility(
                            visible = showBottomBar,
                            enter = fadeIn() + slideInVertically { it },
                            exit = fadeOut() + slideOutVertically { it }
                        ) {
                            ReefBottomNavBar(
                                selectedItem = selectedNavIndex,
                                onItemSelected = { index ->
                                    val options = androidx.navigation.navOptions {
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                    when (index) {
                                        0 -> navController.navigate(Screen.Home) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = false
                                        }

                                        1 -> navController.navigate(
                                            Screen.Usage,
                                            options
                                        )

                                        2 -> navController.navigate(
                                            Screen.Timer,
                                            options
                                        )

                                        3 -> navController.navigate(
                                            Screen.Settings,
                                            options
                                        )
                                    }
                                }
                            )
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                PaddingValues(
                                    innerPadding.calculateStartPadding(LayoutDirection.Ltr),
                                    0.dp,
                                    innerPadding.calculateEndPadding(LayoutDirection.Ltr),
                                    innerPadding.calculateBottomPadding()
                                )
                            ),
                        enterTransition = {
                            fadeIn(animationSpec = tween(300)) +
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 300f
                                        )
                                    )
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(300)) +
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 300f
                                        )
                                    )
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(300)) +
                                    slideIntoContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 300f
                                        )
                                    )
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(300)) +
                                    slideOutOfContainer(
                                        towards = AnimatedContentTransitionScope.SlideDirection.End,
                                        animationSpec = spring(
                                            dampingRatio = 0.8f,
                                            stiffness = 300f
                                        )
                                    )
                        }
                    ) {
                        composable<Screen.Home> {
                            HomeContent(
                                onNavigateToTimer = { navController.navigate(Screen.Timer) },
                                onNavigateToUsage = { navController.navigate(Screen.Usage) },
                                onNavigateToRoutines = { navController.navigate(Screen.Routines) },
                                onNavigateToWhitelist = { navController.navigate(Screen.Whitelist) },
                                onNavigateToWebsiteBlocklist = { navController.navigate(Screen.WebsiteBlocklist) },
                                onNavigateToIntro = {
                                    startActivity(
                                        Intent(
                                            this@MainActivity,
                                            AppIntroActivity::class.java
                                        )
                                    )
                                },
                                onRequestAccessibility = { /* no-op: accessibility no longer required for focus */ },
                                currentTimeLeft = currentTimeLeft,
                                currentTimerState = currentTimerState,
                                whitelistedAppsCount = whitelistedCount,
                                dailyUsageText = dailyUsageText
                            )
                        }

                        composable<Screen.Timer> {
                            TimerContent(
                                navController = navController,
                                isTimerRunning = timerState.isRunning,
                                isPaused = timerState.isPaused,
                                currentTimeLeft = currentTimeLeft,
                                currentTimerState = currentTimerState,
                                isStrictMode = timerState.isStrictMode,
                                onStartTimer = { config -> startFocusMode(config) },
                                onPauseTimer = { pauseFocusMode() },
                                onResumeTimer = { resumeFocusMode() },
                                onCancelTimer = { cancelFocusMode() },
                                onSkipTimer = { skipBreak() },
                                onRestartTimer = { restartFocusMode() },
                                onTakeBreak = { takeBreak() }
                            )
                        }

                        composable<Screen.Usage> {
                            UsageScreenWrapper(
                                context = this@MainActivity,
                                usageStatsManager = usageStatsManager,
                                launcherApps = launcherApps,
                                packageManager = packageManager,
                                currentPackageName = packageName,
                                onBackPressed = { navController.popBackStack() },
                                onAppClick = { appUsageStats ->
                                    navController.navigate(Screen.DailyLimit(appUsageStats.applicationInfo.packageName))
                                }
                            )
                        }

                        composable<Screen.DailyLimit> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.DailyLimit>()
                            val pkgName = route.packageName
                            val application =
                                remember(pkgName) { packageManager.getApplicationInfo(pkgName, 0) }
                            val appIcon = remember(application) {
                                packageManager.getApplicationIcon(application)
                            }
                            val appName = remember(application) {
                                packageManager.getApplicationLabel(application).toString()
                            }
                            val existingLimitMinutes =
                                remember(pkgName) { (AppLimits.getLimit(pkgName) / 60000).toInt() }
                            var weekOffset by remember { mutableIntStateOf(0) }
                            val dailyData by remember(pkgName, weekOffset) {
                                derivedStateOf {
                                    getDailyUsageForLastWeek(
                                        pkgName,
                                        usageStatsManager,
                                        weekOffset
                                    )
                                }
                            }

                            DailyLimitScreen(
                                appName = appName,
                                appIcon = appIcon,
                                packageName = pkgName,
                                existingLimitMinutes = existingLimitMinutes,
                                dailyData = dailyData,
                                onSave = { minutes ->
                                    AppLimits.setLimit(pkgName, minutes)
                                    AppLimits.save()
                                    navController.popBackStack()
                                },
                                onRemove = {
                                    AppLimits.removeLimit(pkgName)
                                    AppLimits.save()
                                    navController.popBackStack()
                                },
                                onBackPressed = { navController.popBackStack() },
                                weekOffset = weekOffset,
                                onWeekChange = { newOffset -> weekOffset = newOffset },
                                canGoPrevious = weekOffset > -4
                            )
                        }

                        composable<Screen.Routines> {
                            RoutinesScreen(
                                onBackPress = { navController.popBackStack() },
                                onCreateRoutine = { navController.navigate(Screen.CreateRoutine(null)) },
                                onEditRoutine = { routine ->
                                    navController.navigate(
                                        Screen.CreateRoutine(
                                            routine.id
                                        )
                                    )
                                }
                            )
                        }

                        composable<Screen.CreateRoutine> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.CreateRoutine>()
                            CreateRoutineScreen(
                                routineId = route.routineId,
                                onBackPressed = { navController.popBackStack() },
                                onSaveComplete = { navController.popBackStack() }
                            )
                        }

                        composable<Screen.Whitelist> {
                            WhitelistScreenWrapper(
                                navController = navController,
                                launcherApps = launcherApps,
                                packageManager = packageManager,
                                currentPackageName = packageName
                            )
                        }

                        composable<Screen.Settings> {
                            SettingsContent(onSoundPicker = { launchSoundPicker() })
                        }

                        composable<Screen.FocusStats> {
                            FocusStatsScreen(
                                onBackPressed = { navController.popBackStack() },
                                onSessionClick = { id ->
                                    navController.navigate(
                                        Screen.FocusSessionDetail(
                                            id
                                        )
                                    )
                                }
                            )
                        }

                        composable<Screen.FocusSessionDetail> { backStackEntry ->
                            val route = backStackEntry.toRoute<Screen.FocusSessionDetail>()
                            FocusSessionDetailScreen(
                                sessionId = route.sessionId,
                                onBackPressed = { navController.popBackStack() }
                            )
                        }

                        composable<Screen.WebsiteBlocklist> {
                            WebsiteBlocklistScreen(onBackPressed = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }

    private fun launchSoundPicker() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TITLE,
                getString(R.string.select_transition_sound)
            )
            val currentSound = prefs.getString("pomodoro_sound", null)
            if (!currentSound.isNullOrEmpty()) {
                putExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                    currentSound.toUri()
                )
            }
        }
        soundPickerLauncher.launch(intent)
    }

    private fun startFocusMode(config: TimerConfig) {
        when (config) {
            is TimerConfig.Simple -> prefs.edit {
                putBoolean("focus_mode", true)
                putBoolean("pomodoro_mode", false)
                putBoolean("count_up_mode", false)
                putLong("focus_time", config.minutes * 60 * 1000L)
                putBoolean("strict_mode", config.strictMode)
            }

            is TimerConfig.Pomodoro -> prefs.edit {
                putBoolean("focus_mode", true)
                putBoolean("pomodoro_mode", true)
                putBoolean("count_up_mode", false)
                putLong("focus_time", config.focusMinutes * 60 * 1000L)
                putLong("pomodoro_focus_duration", config.focusMinutes * 60 * 1000L)
                putLong("pomodoro_short_break_duration", config.shortBreakMinutes * 60 * 1000L)
                putLong("pomodoro_long_break_duration", config.longBreakMinutes * 60 * 1000L)
                putInt("pomodoro_cycles_before_long_break", config.cycles)
                putInt("pomodoro_current_cycle", 1)
                putString("pomodoro_state", "FOCUS")
                putBoolean("strict_mode", config.strictMode)
            }

            is TimerConfig.CountUp -> prefs.edit {
                putBoolean("focus_mode", true)
                putBoolean("pomodoro_mode", false)
                putBoolean("count_up_mode", true)
                putFloat("count_up_ratio", config.ratio.toFloat())
                putBoolean("strict_mode", config.strictMode)
            }
        }
        startForegroundService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_START
        })
    }

    private fun takeBreak() {
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_TAKE_BREAK
        })
    }

    private fun skipBreak() {
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_SKIP_BREAK
        })
    }

    private fun pauseFocusMode() {
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_PAUSE
        })
    }

    private fun resumeFocusMode() {
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_RESUME
        })
    }

    private fun restartFocusMode() {
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_RESTART
        })
    }

    private fun cancelFocusMode() {
        // Use the new ACTION_STOP which handles watchdog/resilience/persistence cleanup
        startService(Intent(this, FocusModeService::class.java).apply {
            action = FocusModeService.ACTION_STOP
        })
        prefs.edit {
            putBoolean("focus_mode", false)
            remove("strict_mode")
        }
    }

    override fun onResume() {
        super.onResume()
        if (!prefs.getBoolean("first_run", true)) {
            val missingPermissions = checkAllPermissions().filter { !it.isGranted && !it.isOptional }
            if (missingPermissions.isNotEmpty()) {
                startActivity(Intent(this, dev.pranav.reef.intro.AppIntroActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                return
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(timerReceiver)
        } catch (_: Exception) {
        }
        val timerState = TimerStateManager.state.value
        if (!timerState.isRunning && !timerState.isPaused) {
            prefs.edit {
                putBoolean("focus_mode", false)
                remove("strict_mode")
            }
        }
    }

    private fun addExceptions() {
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
        packageManager.queryIntentActivities(intent, 0).forEach {
            val packageName = it.activityInfo.packageName
            if (!Whitelist.isWhitelisted(packageName)) Whitelist.whitelist(packageName)
        }
    }
}

@Composable
private fun ReefBottomNavBar(
    selectedItem: Int,
    onItemSelected: (Int) -> Unit
) {
    val items = listOf(
        Triple(Icons.Rounded.Home,        stringResource(R.string.nav_home),     0),
        Triple(Icons.Rounded.BarChart,    stringResource(R.string.nav_stats),    1),
        Triple(Icons.Rounded.EmojiNature, stringResource(R.string.nav_focus),    2),
        Triple(Icons.Rounded.Settings,    stringResource(R.string.nav_settings), 3),
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .navigationBarsPadding(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (icon, label, index) ->
                BottomNavItem(
                    icon = icon,
                    label = label,
                    selected = selectedItem == index,
                    onClick = { onItemSelected(index) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val iconTint by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) onPrimaryContainer else onSurfaceVariant,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "navIconTint"
    )
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "navBgColor"
    )
    val textColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) onPrimaryContainer else onSurfaceVariant,
        animationSpec = androidx.compose.animation.core.tween(220),
        label = "navTextColor"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .widthIn(min = 64.dp)
    ) {
        // Pill indicator around the icon
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(22.dp),
                tint = iconTint
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1
        )
        Spacer(Modifier.height(2.dp))
    }
}
