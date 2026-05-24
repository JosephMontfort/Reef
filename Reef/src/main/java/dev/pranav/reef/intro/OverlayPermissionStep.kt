package dev.pranav.reef.intro

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.reef.R
import dev.pranav.reef.util.OemOverlayManager
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun OverlayPermissionStep(
    onPermissionsFullyGranted: () -> Unit
) {
    val context = LocalContext.current
    
    var lifecycleTrigger by remember { mutableIntStateOf(0) }
    
    val standardGranted = Settings.canDrawOverlays(context)
    val oemGranted = remember(lifecycleTrigger, standardGranted) { OemOverlayManager.isOemOverlayGranted(context) }
    val needsOem = remember { OemOverlayManager.getOemOverlayIntent(context) != null }

    // 3-Second Timer State
    var timerTicks by remember { mutableIntStateOf(3) }
    
    LaunchedEffect(standardGranted, needsOem, oemGranted) {
        if (standardGranted && needsOem && !oemGranted) {
            timerTicks = 3
            while (timerTicks > 0) {
                delay(1000)
                timerTicks--
            }
        }
    }

    val manufacturer = remember { Build.MANUFACTURER.lowercase(Locale.ROOT) }
    val exactPermissionName = remember {
        if (manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") || manufacturer.contains("blackshark")) {
            "\"Display pop-up windows while running in the background\""
        } else {
            "\"Floating Windows\" or \"Display over other apps\""
        }
    }

    val standardLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        lifecycleTrigger++ 
        if (Settings.canDrawOverlays(context) && (!needsOem || OemOverlayManager.isOemOverlayGranted(context))) {
            onPermissionsFullyGranted()
        }
    }

    val oemLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        OemOverlayManager.markOemInteracted(context)
        lifecycleTrigger++
        if (Settings.canDrawOverlays(context) && OemOverlayManager.isOemOverlayGranted(context)) {
            onPermissionsFullyGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Layers,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        AnimatedContent(targetState = standardGranted, label = "OverlayStateTransition") { isStandardGranted ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                
                // STATE 1: Ask for standard permission
                if (!isStandardGranted) {
                    Text(
                        text = stringResource(R.string.overlay_permission),
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.overlay_permission_description),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            standardLauncher.launch(intent)
                        },
                        border = BorderStroke(1.dp, Color.White),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("Grant Permission")
                    }
                } 
                // STATE 2: Standard granted, but OEM special background permission needed
                else if (needsOem && !oemGranted) {
                    Text(
                        text = "Crucial Extra Step",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your device strictly blocks apps from showing focus screens. You MUST find and enable ",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        text = exactPermissionName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                    Text(
                        text = " on the next screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    OutlinedButton(
                        onClick = {
                            val intent = OemOverlayManager.getOemOverlayIntent(context)
                            if (intent != null) {
                                try {
                                    oemLauncher.launch(intent)
                                } catch (e: Exception) {
                                    OemOverlayManager.markOemInteracted(context)
                                    lifecycleTrigger++
                                }
                            }
                        },
                        enabled = timerTicks == 0,
                        border = BorderStroke(1.dp, if (timerTicks == 0) Color.White else Color.White.copy(alpha = 0.4f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.4f)
                        )
                    ) {
                        Text(if (timerTicks > 0) "Please read carefully ($timerTicks)" else "Grant Additional Permission")
                    }
                } 
                // STATE 3: Both standard and OEM are fully granted
                else {
                    Text(
                        text = "All Ready!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "✓ Required permissions granted. Please click the Next arrow below.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                    // REMOVED THE NON-FUNCTIONAL INNER "NEXT" BUTTON
                }
            }
        }
    }
}
