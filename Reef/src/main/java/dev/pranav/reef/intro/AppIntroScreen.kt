package dev.pranav.reef.intro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import dev.pranav.appintro.AppIntro
import dev.pranav.appintro.IntroPage
import dev.pranav.reef.R
import dev.pranav.reef.routine.Routines
import dev.pranav.reef.ui.ReefTheme
import dev.pranav.reef.util.*

class AppIntroActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ReefTheme { AppIntroScreen() }
            BackHandler {}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("BatteryLife")
@Composable
fun AppIntroScreen() {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val requestPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val onFinishCallback = {
        prefs.edit { putBoolean("first_run", false) }
        activity!!.finish()
    }

    // Immediate UI update trigger using lifecycle observer
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumeTrigger by remember { mutableIntStateOf(0) }
    
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                resumeTrigger++ 
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Dialog state variables
    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var showAutostartDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showNotificationDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showDndDialog by remember { mutableStateOf(false) }

    var autostartVerified by remember { mutableStateOf(false) }
    var autostartInteracted by remember { mutableStateOf(false) }
    var autostartVerifyTicks by remember { mutableIntStateOf(3) }
    
    LaunchedEffect(autostartInteracted, autostartVerified, resumeTrigger) {
        if (autostartInteracted && !autostartVerified) {
            autostartVerifyTicks = 3
            while (autostartVerifyTicks > 0) {
                kotlinx.coroutines.delay(1000)
                autostartVerifyTicks--
            }
        }
    }

    val pages = mutableListOf<IntroPage>()

    // 1. Welcome Slide
    pages.add(
        IntroPage(
            title = stringResource(R.string.app_name),
            description = stringResource(R.string.app_description),
            icon = Icons.Rounded.HourglassTop,
            backgroundColor = Color(0xFF093A8F),
            contentColor = Color.White,
            onNext = { true }
        )
    )

    // 2. Accessibility Service (OPTIONAL)
    pages.add(
        IntroPage(
            title = "", description = "", backgroundColor = Color(0xFF607D8B), contentColor = Color.White,
            onNext = { true },
            customContent = {
                val accessGranted = remember(resumeTrigger) { context.isAccessibilityServiceEnabledForBlocker() }
                AnimatedCustomSlide(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = if (accessGranted) "Accessibility Granted" else stringResource(R.string.accessibility_service),
                    description = if (accessGranted) "✓ Required permissions granted. Please click the Next arrow below." else stringResource(R.string.accessibility_service_website_only_description)
                ) {
                    if (!accessGranted) {
                        OutlinedButton(
                            onClick = { showAccessibilityDialog = true },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text(stringResource(R.string.grant_optional)) }
                    }
                }
            }
        )
    )

    // 3. Display Over Other Apps (MANDATORY, Custom Xiaomi OEM Logic Preserved)
    pages.add(
        IntroPage(
            title = "", description = "", backgroundColor = Color(0xFFE65100), contentColor = Color.White,
            onNext = {
                val hasStandard = Settings.canDrawOverlays(context)
                val hasOem = dev.pranav.reef.util.OemOverlayManager.isOemOverlayGranted(context)
                if (!hasStandard || !hasOem) {
                    Toast.makeText(context, "Please complete the required background permissions", Toast.LENGTH_SHORT).show()
                    false
                } else true
            },
            customContent = { OverlayPermissionStep(onPermissionsFullyGranted = {}) }
        )
    )

    // 4. Autostart (MANDATORY IF REQUIRED)
    val needsAutostart = remember { !context.doesNotNeedAutostartGrant() }
    if (needsAutostart) {
        pages.add(
            IntroPage(
                title = "", description = "", backgroundColor = Color(0xFF1B5E20), contentColor = Color.White,
                onNext = { 
                    if (!autostartVerified) {
                        Toast.makeText(context, "Please complete Autostart permission setup", Toast.LENGTH_SHORT).show()
                        false
                    } else true
                },
                customContent = {
                    AnimatedCustomSlide(
                        icon = Icons.Rounded.RestartAlt,
                        title = if (autostartVerified) "Autostart Configured" else if (autostartInteracted) "Did you grant it?" else stringResource(R.string.autostart_permission),
                        description = if (autostartVerified) "✓ Autostart verified. Please click the Next arrow below." else if (autostartInteracted) "Did you successfully find and enable Autostart/Auto-launch?" else stringResource(R.string.autostart_permission_description)
                    ) {
                        if (!autostartVerified && !autostartInteracted) {
                            OutlinedButton(
                                onClick = { showAutostartDialog = true },
                                border = BorderStroke(1.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Grant Permission") }
                        } else if (!autostartVerified && autostartInteracted) {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedButton(
                                    onClick = { autostartInteracted = false },
                                    border = BorderStroke(1.dp, Color.White),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                                ) { Text("No, try again") }
                                OutlinedButton(
                                    onClick = { autostartVerified = true },
                                    enabled = autostartVerifyTicks == 0,
                                    border = BorderStroke(1.dp, if (autostartVerifyTicks == 0) Color.White else Color.White.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))
                                ) { Text(if (autostartVerifyTicks > 0) "Yes, I enabled it ($autostartVerifyTicks)" else "Yes, I enabled it") }
                            }
                        }
                    }
                }
            )
        )
    }

    // 5. Usage Statistics (MANDATORY)
    pages.add(
        IntroPage(
            title = "", description = "", backgroundColor = Color(0xFF536DFE), contentColor = Color.White,
            onNext = {
                if (!context.hasUsageStatsPermission()) {
                    Toast.makeText(context, "Please grant Usage Statistics permission", Toast.LENGTH_SHORT).show()
                    false
                } else true
            },
            customContent = {
                val hasUsage = remember(resumeTrigger) { context.hasUsageStatsPermission() }
                AnimatedCustomSlide(
                    icon = Icons.Rounded.QueryStats,
                    title = if (hasUsage) "Usage Access Granted" else stringResource(R.string.app_usage_statistics),
                    description = if (hasUsage) "✓ Required permissions granted. Please click the Next arrow below." else stringResource(R.string.app_usage_statistics_description)
                ) {
                    if (!hasUsage) {
                        OutlinedButton(
                            onClick = { showUsageDialog = true },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Grant Permission") }
                    }
                }
            }
        )
    )

    // 6. Notification Permission (MANDATORY Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pages.add(
            IntroPage(
                title = "", description = "", backgroundColor = Color(0xFFF19C32), contentColor = Color.White,
                onNext = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        Toast.makeText(context, "Please grant Notification permission", Toast.LENGTH_SHORT).show()
                        false
                    } else true
                },
                customContent = {
                    val hasNotifs = remember(resumeTrigger) { ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
                    AnimatedCustomSlide(
                        icon = Icons.Rounded.NotificationsActive,
                        title = if (hasNotifs) "Notifications Granted" else stringResource(R.string.notification_permission),
                        description = if (hasNotifs) "✓ Required permissions granted. Please click the Next arrow below." else stringResource(R.string.notification_permission_description)
                    ) {
                        if (!hasNotifs) {
                            OutlinedButton(
                                onClick = { showNotificationDialog = true },
                                border = BorderStroke(1.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Grant Permission") }
                        }
                    }
                }
            )
        )
    }

    // 7. Battery Optimization (MANDATORY)
    pages.add(
        IntroPage(
            title = "", description = "", backgroundColor = Color(0xFF00BFA5), contentColor = Color.White,
            onNext = {
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                if (!isIgnoring) {
                    Toast.makeText(context, "Please disable battery optimization", Toast.LENGTH_SHORT).show()
                    false
                } else {
                    Routines.saveAll(Routines.createDefaults(), context)
                    prefs.edit { putBoolean("first_run", false) }
                    true
                }
            },
            customContent = {
                val isIgnoring = remember(resumeTrigger) { powerManager.isIgnoringBatteryOptimizations(context.packageName) }
                AnimatedCustomSlide(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = if (isIgnoring) "Battery Exception Granted" else stringResource(R.string.battery_optimization_exception),
                    description = if (isIgnoring) "✓ Required permissions granted. Please click the Next arrow below." else stringResource(R.string.battery_optimization_exception_description)
                ) {
                    if (!isIgnoring) {
                        OutlinedButton(
                            onClick = { showBatteryDialog = true },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Grant Permission") }
                    }
                }
            }
        )
    )

    // 8. Do Not Disturb (MANDATORY Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pages.add(
            IntroPage(
                title = "", description = "", backgroundColor = Color(0xFF8968D5), contentColor = Color.White,
                onNext = {
                    if (!context.hasDndPermission()) {
                        Toast.makeText(context, "Please grant Do Not Disturb permission", Toast.LENGTH_SHORT).show()
                        false
                    } else true
                },
                customContent = {
                    val hasDnd = remember(resumeTrigger) { context.hasDndPermission() }
                    AnimatedCustomSlide(
                        icon = Icons.Rounded.DoNotDisturbOn,
                        title = if (hasDnd) "Do Not Disturb Granted" else stringResource(R.string.do_not_disturb_permission),
                        description = if (hasDnd) "✓ Required permissions granted. Please click the Next arrow below." else stringResource(R.string.do_not_disturb_permission_description)
                    ) {
                        if (!hasDnd) {
                            OutlinedButton(
                                onClick = { showDndDialog = true },
                                border = BorderStroke(1.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Grant Permission") }
                        }
                    }
                }
            )
        )
    }

    AppIntro(
        pages = pages,
        onFinish = onFinishCallback,
        onSkip = { activity?.finishAffinity() },
        showSkipButton = false,
        useAnimatedPager = true,
        nextButtonText = stringResource(R.string.next),
        finishButtonText = stringResource(R.string.get_started)
    )

    // Independent Material 3 Dialogs triggered by the UI buttons above
    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            title = { Text(stringResource(R.string.accessibility_service)) },
            text = { Text(stringResource(R.string.accessibility_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDialog = false
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    
    if (showAutostartDialog) {
        AlertDialog(
            onDismissRequest = { showAutostartDialog = false },
            title = { Text(stringResource(R.string.autostart_permission)) },
            text = { Text(stringResource(R.string.autostart_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutostartDialog = false
                    autostartInteracted = true
                    activity?.requestAutostartPermission()
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutostartDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showUsageDialog) {
        AlertDialog(
            onDismissRequest = { showUsageDialog = false },
            title = { Text(stringResource(R.string.usage_access)) },
            text = { Text(stringResource(R.string.usage_access_description)) },
            confirmButton = {
                TextButton(onClick = {
                    showUsageDialog = false
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showUsageDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            title = { Text(stringResource(R.string.notification_permission)) },
            text = { Text(stringResource(R.string.notification_permission_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationDialog = false
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text(stringResource(R.string.battery_optimization_exception)) },
            text = { Text(stringResource(R.string.battery_optimization_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showBatteryDialog = false
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showDndDialog) {
        AlertDialog(
            onDismissRequest = { showDndDialog = false },
            title = { Text(stringResource(R.string.do_not_disturb_permission)) },
            text = { Text(stringResource(R.string.do_not_disturb_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    showDndDialog = false
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showDndDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
