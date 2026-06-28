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
        }
        BackHandler {}
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

    // 1. Immediate UI update trigger using lifecycle observer
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

    val pages = mutableListOf<IntroPage>()

    // Page 1: Welcome Slide
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

    // Page 2: Accessibility Service (OPTIONAL)
    pages.add(
        IntroPage(
            title = "", description = "", backgroundColor = Color(0xFF607D8B), contentColor = Color.White,
            onNext = { true }, // Always returns true so Next button just navigates
            customContent = {
                val accessGranted = remember(resumeTrigger) { context.isAccessibilityServiceEnabledForBlocker() }
                AnimatedCustomSlide(
                    icon = Icons.Rounded.AccessibilityNew,
                    title = if (accessGranted) "Accessibility Granted" else stringResource(R.string.accessibility_service),
                    description = if (accessGranted) "✓ Accessibility granted. You can proceed." else stringResource(R.string.accessibility_service_website_only_description)
                ) {
                    if (!accessGranted) {
                        OutlinedButton(
                            onClick = { showAccessibilityDialog = true }, // Separate grant button
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text(stringResource(R.string.grant_optional)) }
                    }
                }
            }
        )
    )

    // Page 3: Display Over Other Apps (MANDATORY, Custom Logic)
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

    // Page 4: Autostart (MANDATORY IF REQUIRED)
    if (!context.doesNotNeedAutostartGrant()) {
        pages.add(
            IntroPage(
                title = "", description = "", backgroundColor = Color(0xFF1B5E20), contentColor = Color.White,
                onNext = { 
                    if (!context.doesNotNeedAutostartGrant()) {
                        Toast.makeText(context, "Please grant Autostart permission", Toast.LENGTH_SHORT).show()
                        false
                    } else true
                },
                customContent = {
                    val autoGranted = remember(resumeTrigger) { context.doesNotNeedAutostartGrant() }
                    AnimatedCustomSlide(
                        icon = Icons.Rounded.RestartAlt,
                        title = if (autoGranted) "Autostart Granted" else stringResource(R.string.autostart_permission),
                        description = if (autoGranted) "✓ Autostart granted. You can proceed." else stringResource(R.string.autostart_permission_description)
                    ) {
                        if (!autoGranted) {
                            OutlinedButton(
                                onClick = { showAutostartDialog = true }, // Separate grant button
                                border = BorderStroke(1.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Grant Permission") }
                        }
                    }
                }
            )
        )
    }

    // -- Note: Usage, Notification, Battery, and DND pages will be appended in Phase 2 --

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
            text = { Text(stringResource(R.string.accessibility_service_website_only_description)) },
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
            text = { Text(stringResource(R.string.autostart_permission_description)) },
            confirmButton = {
                TextButton(onClick = {
                    showAutostartDialog = false
                    activity?.requestAutostartPermission()
                }) { Text(stringResource(R.string.agree)) }
            },
            dismissButton = {
                TextButton(onClick = { showAutostartDialog = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
