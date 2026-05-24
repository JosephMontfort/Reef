package dev.pranav.reef.intro

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import dev.pranav.reef.util.doesNotNeedAutostartGrant
import dev.pranav.reef.util.hasOverlayPermission
import dev.pranav.reef.util.requestAutostartPermission
import dev.pranav.reef.util.requestOverlayPermission
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

    val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

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

        // 2. Accessibility Service — OPTIONAL, only required for website blocking.
        // App blocking via UsageStats works without it. Users can grant it later from the
        // Website Blocklist screen when they first try to use website limits.
        IntroPage(
            title = stringResource(R.string.accessibility_service),
            description = "",
            backgroundColor = Color(0xFF607D8B),
            contentColor = Color.White,
            onNext = { true },
            customContent = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AccessibilityNew,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.accessibility_service),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.accessibility_service_website_only_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    val accessGranted = context.isAccessibilityServiceEnabledForBlocker()
                    if (!accessGranted) {
                        OutlinedButton(
                            onClick = { activity?.showAccessibilityDialog() },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text(stringResource(R.string.grant_optional))
                        }
                    } else {
                        Text(
                            text = "✓ ${stringResource(R.string.accessibility_service)} granted",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        ),

        // 3. Display Over Other Apps — MANDATORY (Multi-Step OEM Flow)
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
                // Wrap the component in a dark-mode styled theme so the text 
                // and buttons blend beautifully with the orange slide background
                MaterialTheme(
                    colorScheme = MaterialTheme.colorScheme.copy(
                        onSurface = Color.White,
                        onSurfaceVariant = Color.White.copy(alpha = 0.85f),
                        primary = Color.White,
                        onPrimary = Color(0xFFE65100)
                    )
                ) {
                    OverlayPermissionStep(
                        onPermissionsFullyGranted = {
                            // Left empty because once the UI shifts to "Permissions Granted!",
                            // the user can simply click the app's native Next button.
                        }
                    )
                }
            }
        ),

        // 4. Autostart — MANDATORY on Chinese OEMs, auto-passes on stock Android
        if (!doesNotNeedAutostartGrant()) {
            IntroPage(
                title = stringResource(R.string.autostart_permission),
                description = "",
                backgroundColor = Color(0xFF1B5E20),
                contentColor = Color.White,
                onNext = {
                    // On Chinese OEMs there is no API to verify autostart state, so we
                    // trust the user tapped the button and accepted. Allow proceeding.
                    true
                },
                customContent = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.RestartAlt, null, Modifier.size(72.dp), tint = Color.White)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.autostart_permission),
                            style = MaterialTheme.typography.headlineMedium, color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.autostart_permission_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        OutlinedButton(
                            onClick = { activity?.requestAutostartPermission() },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Open Autostart Settings") }
                    }
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
                    activity?.showUsageAccessDialog {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        context.startActivity(intent)
                    }
                    false
                } else true
            }
        ),

        // 6. Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            IntroPage(
                title = stringResource(R.string.notification_permission),
                description = stringResource(R.string.notification_permission_description),
                icon = Icons.Rounded.NotificationsActive,
                backgroundColor = Color(0xFFF19C32),
                contentColor = Color.White,
                onNext = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED

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
                    val intent =
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
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

        // 8. Do Not Disturb Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasDndPermission()) {
            IntroPage(
                title = stringResource(R.string.do_not_disturb_permission),
                description = stringResource(R.string.do_not_disturb_permission_description),
                icon = Icons.Rounded.DoNotDisturbOn,
                backgroundColor = Color(0xFF8968D5),
                contentColor = Color.White,
                onNext = {
                    if (!context.hasDndPermission()) {
                        val intent =
                            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
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
}
