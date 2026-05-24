package dev.pranav.reef.intro

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.reef.util.OemOverlayManager

@Composable
fun OverlayPermissionStep(
    onPermissionsFullyGranted: () -> Unit
) {
    val context = LocalContext.current
    
    // Forces recomposition check when returning from Settings
    var lifecycleTrigger by remember { mutableIntStateOf(0) }
    
    val standardGranted = Settings.canDrawOverlays(context)
    val oemGranted = remember(lifecycleTrigger, standardGranted) { OemOverlayManager.isOemOverlayGranted(context) }
    val needsOem = remember { OemOverlayManager.getOemOverlayIntent(context) != null }

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
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedContent(targetState = standardGranted, label = "OverlayStateTransition") { isStandardGranted ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                
                // STATE 1: Ask for standard permission
                if (!isStandardGranted) {
                    Text(
                        text = "Display Over Other Apps",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Reef requires this standard permission to display focus screens and block distracting apps. Please grant it in the next screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        standardLauncher.launch(intent)
                    }) {
                        Text("Grant Permission")
                    }
                } 
                // STATE 2: Standard granted, but OEM special background permission needed
                else if (needsOem && !oemGranted) {
                    Text(
                        text = "Background Execution Needed",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "To ensure Reef works perfectly in the background, your device requires one extra permission. Please enable \"Display pop-up windows while running in the background\" or \"Floating Windows\" in the upcoming settings page.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        val intent = OemOverlayManager.getOemOverlayIntent(context)
                        if (intent != null) {
                            try {
                                oemLauncher.launch(intent)
                            } catch (e: Exception) {
                                OemOverlayManager.markOemInteracted(context)
                                lifecycleTrigger++
                            }
                        }
                    }) {
                        Text("Grant Additional Permission")
                    }
                } 
                // STATE 3: Both standard and OEM are fully granted
                else {
                    Text(
                        text = "Permissions Granted!",
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onPermissionsFullyGranted) {
                        Text("Next")
                    }
                }
            }
        }
    }
}
