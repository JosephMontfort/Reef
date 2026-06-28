package dev.pranav.reef.intro

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pranav.reef.util.OemOverlayManager
import kotlinx.coroutines.delay

@Composable
fun OverlayPermissionStep(
    onPermissionsFullyGranted: () -> Unit
) {
    val context = LocalContext.current
    var lifecycleTrigger by remember { mutableIntStateOf(0) }
    
    val standardGranted = Settings.canDrawOverlays(context)
    
    var oemIntent by remember { mutableStateOf<Intent?>(null) }
    var needsOem by remember { mutableStateOf(false) }
    var isOemGranted by remember { mutableStateOf(true) }
    var isChecking by remember { mutableStateOf(true) }

    LaunchedEffect(lifecycleTrigger, standardGranted) {
        isChecking = true
        OemOverlayManager.init(context)
        oemIntent = OemOverlayManager.getOemOverlayIntent(context)
        needsOem = oemIntent != null
        isOemGranted = OemOverlayManager.isOemOverlayGrantedAsync(context)
        isChecking = false
    }
    
    var oemInteracted by remember { mutableStateOf(false) }
    
    var timerTicks by remember { mutableIntStateOf(5) }
    var verifyTicks by remember { mutableIntStateOf(3) }
    var hasTimerRun by remember { mutableStateOf(false) }
    
    LaunchedEffect(standardGranted, needsOem, isOemGranted, oemInteracted) {
        if (standardGranted && needsOem && !isOemGranted && !oemInteracted) {
            if (!hasTimerRun) {
                timerTicks = 5
                while (timerTicks > 0) {
                    delay(1000)
                    timerTicks--
                }
                hasTimerRun = true
            } else {
                timerTicks = 0
            }
        }
    }
    
    LaunchedEffect(oemInteracted, isOemGranted, isChecking) {
        if (!isChecking && oemInteracted && !isOemGranted) {
            verifyTicks = 3
            while (verifyTicks > 0) {
                delay(1000)
                verifyTicks--
            }
        }
    }

    val standardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lifecycleTrigger++
    }

    val oemLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        oemInteracted = true
        lifecycleTrigger++
    }

    val exactPermissionName = "\n\n➡️ [ DISPLAY POP-UP WINDOWS WHILE RUNNING IN THE BACKGROUND ] ⬅️\n\nor\n\n➡️ [ FLOATING WINDOWS ] ⬅️\n"

    val title: String
    val desc: String
    val currentIcon: androidx.compose.ui.graphics.vector.ImageVector
    
    if (isChecking) {
        title = "Checking Permissions..."
        desc = "Please wait while we verify your device capabilities."
        currentIcon = Icons.Rounded.HourglassEmpty
    } else if (!standardGranted) {
        title = "Display Over Other Apps"
        desc = "Reef requires this standard permission to display focus screens and block distracting apps. Please grant it in the next screen."
        currentIcon = Icons.Rounded.Layers
    } else if (needsOem && !isOemGranted && !oemInteracted) {
        title = "Crucial Extra Step"
        desc = "Your device strictly blocks apps from showing focus screens. You MUST find and enable $exactPermissionName on the next screen."
        currentIcon = Icons.Rounded.Layers
    } else if (needsOem && !isOemGranted && oemInteracted) {
        title = "Did you grant it?"
        desc = "Did you successfully find and enable $exactPermissionName?"
        currentIcon = Icons.Rounded.Layers
    } else {
        title = "Overlay Configured!"
        desc = "✓ Required permissions granted. Please click the Next arrow below."
        currentIcon = Icons.Rounded.Layers
        LaunchedEffect(Unit) { onPermissionsFullyGranted() }
    }

    AnimatedCustomSlide(
        icon = currentIcon,
        title = title,
        description = desc
    ) {
        AnimatedContent(targetState = "$isChecking-$standardGranted-$oemInteracted-$isOemGranted", label = "") { _ ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isChecking) {
                    CircularProgressIndicator(color = Color.White)
                } else if (!standardGranted) {
                    OutlinedButton(
                        onClick = { standardLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) },
                        border = BorderStroke(1.dp, Color.White),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Grant Permission")
                    }
                } else if (needsOem && !isOemGranted && !oemInteracted) {
                    OutlinedButton(
                        onClick = { 
                            try {
                                oemIntent?.let { oemLauncher.launch(it) }
                            } catch (e: Exception) {
                                // If it crashes here, the Custom ROM was spoofing the intent existence.
                                // We automatically bypass the OEM step.
                                OemOverlayManager.markOemVerified(context, true)
                                lifecycleTrigger++
                                onPermissionsFullyGranted()
                            }
                        },
                        enabled = timerTicks == 0,
                        border = BorderStroke(1.dp, if (timerTicks == 0) Color.White else Color.White.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))
                    ) {
                        Text(if (timerTicks > 0) "Please read carefully ($timerTicks)" else "Open Settings")
                    }
                } else if (needsOem && !isOemGranted && oemInteracted) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(
                            onClick = { 
                                oemInteracted = false 
                                timerTicks = 0
                            },
                            border = BorderStroke(1.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("No, try again")
                        }
                        OutlinedButton(
                            onClick = { 
                                OemOverlayManager.markOemVerified(context, true)
                                lifecycleTrigger++
                                onPermissionsFullyGranted()
                            },
                            enabled = verifyTicks == 0,
                            border = BorderStroke(1.dp, if (verifyTicks == 0) Color.White else Color.White.copy(alpha = 0.4f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f))
                        ) {
                            Text(if (verifyTicks > 0) "Yes, I enabled it ($verifyTicks)" else "Yes, I enabled it")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedCustomSlide(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    val contentVisible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { contentVisible.targetState = true }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)
    ) {
        androidx.compose.animation.AnimatedVisibility(
            visibleState = contentVisible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300)) + 
                    androidx.compose.animation.scaleIn(
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        initialScale = 0.8f
                    )
        ) {
            Surface(
                modifier = Modifier.size(140.dp).clip(androidx.compose.foundation.shape.CircleShape),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = Color.White.copy(alpha = 0.15f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.padding(32.dp).size(76.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        androidx.compose.animation.AnimatedVisibility(
            visibleState = contentVisible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300, delayMillis = 100)) +
                    androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        initialOffsetY = { it / 3 }
                    )
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.animation.AnimatedVisibility(
            visibleState = contentVisible,
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(300, delayMillis = 200)) +
                    androidx.compose.animation.slideInVertically(
                        animationSpec = androidx.compose.animation.core.spring(
                            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                        ),
                        initialOffsetY = { it / 4 }
                    )
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(0.9f)
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                content()
            }
        }
    }
}
