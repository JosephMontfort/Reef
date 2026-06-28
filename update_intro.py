import os

target_file = "Reef/src/main/java/dev/pranav/reef/intro/AppIntroScreen.kt"

new_content = """package dev.pranav.reef.intro

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
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
            ReefTheme {
                AppIntroScreen()
            }

            BackHandler {
            }
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

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
    }

    val onFinishCallback = {
        prefs.edit { putBoolean("first_run", false) }
        activity!!.finish()
    }

    var showAccessibilityDialog by remember { mutableStateOf(false) }
    var showUsageDialog by remember { mutableStateOf(false) }
    var showAutostartDialog by remember { mutableStateOf(false) }

    val pages = listOfNotNull(
        // 1. Welcome Slide
        IntroPage(
            title = stringResource(R.string.app_name),
            description = stringResource(R.string.app_description),
            icon = Icons.Rounded.HourglassTop,
            backgroundColor = Color(0xFF093A8F),
            contentColor = Color.White,
            onNext = { true }
        ),

        // 2. Accessibility Service
        IntroPage(
            title = stringResource(R.string.accessibility_service),
            description = stringResource(R.string.accessibility_service_website_only_description),
            icon = Icons.Rounded.AccessibilityNew,
            backgroundColor = Color(0xFF607D8B),
            contentColor = Color.White,
            onNext = {
                if (!context.isAccessibilityServiceEnabledForBlocker()) {
                    showAccessibilityDialog = true
                    false
                } else true
            }
        ),

        // 3. Display Over Other Apps (Maintained OEM Logic)
        IntroPage(
            title = "",
            description = "",
            backgroundColor = Color(0xFFE65100),
            contentColor = Color.White,
            onNext = {
                val hasStandard = Settings.canDrawOverlays(context)
                val hasOem = dev.pranav.reef.util.OemOverlayManager.isOemOverlayGranted(context)
                
                if (!hasStandard || !hasOem) {
                    android.widget.Toast.makeText(context, "Please complete the required background permissions to proceed", android.widget.Toast.LENGTH_SHORT).show()
                    false
                } else {
                    true
                }
            },
            customContent = {
                OverlayPermissionStep(
                    onPermissionsFullyGranted = {}
                )
            }
        ),

        // 4. Autostart
        if (!context.doesNotNeedAutostartGrant()) {
            IntroPage(
                title = stringResource(R.string.autostart_permission),
                description = stringResource(R.string.autostart_permission_description),
                icon = Icons.Rounded.RestartAlt,
                backgroundColor = Color(0xFF1B5E20),
                contentColor = Color.White,
                onNext = {
                    if (!context.doesNotNeedAutostartGrant()) {
                        showAutostartDialog = true
                        false
                    } else true
                }
            )
        } else null,

        // 5. Usage Statistics
        IntroPage(
            title = stringResource(R.string.app_usage_statistics),
            description = stringResource(R.string.app_usage_statistics_description),
            icon = Icons.Rounded.QueryStats,
            backgroundColor = Color(0xFF536DFE),
            contentColor = Color.White,
            onNext = {
                if (!context.hasUsageStatsPermission()) {
                    showUsageDialog = true
                    false
                } else true
            }
        ),

        // 6. Notification Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntroPage(
                title = stringResource(R.string.notification_permission),
                description = stringResource(R.string.notification_permission_description),
                icon = Icons.Rounded.NotificationsActive,
                backgroundColor = Color(0xFFF19C32),
                contentColor = Color.White,
                onNext = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        false
                    } else true
                }
            )
        } else null,

        // 7. Battery Optimization
        IntroPage(
            title = stringResource(R.string.battery_optimization_exception),
            description = stringResource(R.string.battery_optimization_exception_description),
            icon = Icons.Rounded.BatteryChargingFull,
            backgroundColor = Color(0xFF00BFA5),
            contentColor = Color.White,
            onNext = {
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                if (!isIgnoring) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                    false
                } else {
                    Routines.saveAll(Routines.createDefaults(), context)
                    prefs.edit { putBoolean("first_run", false) }
                    true
                }
            }
        ),

        // 8. Do Not Disturb
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntroPage(
                title = stringResource(R.string.do_not_disturb_permission),
                description = stringResource(R.string.do_not_disturb_permission_description),
                icon = Icons.Rounded.DoNotDisturbOn,
                backgroundColor = Color(0xFF8968D5),
                contentColor = Color.White,
                onNext = {
                    if (!context.hasDndPermission()) {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        context.startActivity(intent)
                        false
                    } else true
                }
            )
        } else null
    )

    AppIntro(
        pages = pages,
        onFinish = onFinishCallback,
        onSkip = { activity?.finishAffinity() },
        showSkipButton = false,
        useAnimatedPager = true,
        nextButtonText = stringResource(R.string.next),
        finishButtonText = stringResource(R.string.get_started)
    )

    // Material 3 Dialogs triggered via the 'Next' Button

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDialog = false },
            title = { Text(stringResource(R.string.accessibility_service)) },
            text = { Text(stringResource(R.string.accessibility_service_website_only_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessibilityDialog = false
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                ) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showUsageDialog) {
        AlertDialog(
            onDismissRequest = { showUsageDialog = false },
            title = { Text(stringResource(R.string.usage_access)) },
            text = { Text(stringResource(R.string.usage_access_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUsageDialog = false
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                ) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showUsageDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (showAutostartDialog) {
        AlertDialog(
            onDismissRequest = { showAutostartDialog = false },
            title = { Text(stringResource(R.string.autostart_permission)) },
            text = { Text(stringResource(R.string.autostart_permission_description)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAutostartDialog = false
                        activity?.requestAutostartPermission()
                    }
                ) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutostartDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
"""

with open(target_file, "w", encoding="utf-8") as f:
    f.write(new_content)
    
print("Successfully backported Dialog styles and restored page animations while maintaining OEM functionality.")
