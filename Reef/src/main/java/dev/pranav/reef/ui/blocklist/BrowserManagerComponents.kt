package dev.pranav.reef.ui.blocklist

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.pranav.reef.R
import dev.pranav.reef.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val defaultBrowserConfigs = mapOf(
    "com.android.chrome" to "Chrome",
    "com.brave.browser" to "Brave",
    "org.mozilla.firefox" to "Firefox",
    "com.opera.browser" to "Opera",
    "com.microsoft.emmx" to "Microsoft Edge",
    "com.duckduckgo.mobile.android" to "DuckDuckGo",
    "com.vivaldi.browser" to "Vivaldi",
    "com.kiwibrowser.browser" to "Kiwi Browser",
    "com.ecosia.android" to "Ecosia",
    "org.torproject.torbrowser" to "Tor Browser"
)

fun getInstalledSupportedBrowsers(context: Context): List<String> {
    val pm = context.packageManager
    val installed = mutableListOf<String>()
    val customSetRaw = prefs.getStringSet("custom_browsers", emptySet()) ?: emptySet()
    val customSet = customSetRaw.map { it.split(";;")[0] }.toSet()
    val allBrowsers = defaultBrowserConfigs.keys + customSet

    for (pkg in allBrowsers.distinct()) {
        try {
            pm.getPackageInfo(pkg, 0)
            val name = defaultBrowserConfigs[pkg] ?: pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            installed.add(name)
        } catch (e: PackageManager.NameNotFoundException) {
            // Browser not installed, skip
        }
    }
    return installed.sorted()
}

data class AppInfo(val packageName: String, val label: String, val icon: Drawable?)

suspend fun getLauncherApps(context: Context): List<AppInfo> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    val resolveInfos = pm.queryIntentActivities(intent, 0)
    
    resolveInfos.map {
        val pkg = it.activityInfo.packageName
        AppInfo(
            packageName = pkg,
            label = it.loadLabel(pm).toString(),
            icon = it.loadIcon(pm)
        )
    }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
}

@Composable
fun SupportedBrowsersCard(onAddCustomClick: () -> Unit) {
    val context = LocalContext.current
    val installedBrowsers by remember { mutableStateOf(getInstalledSupportedBrowsers(context)) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Supported Browsers",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                TextButton(onClick = onAddCustomClick) {
                    Text("Add Custom")
                }
            }
            
            if (installedBrowsers.isEmpty()) {
                Text(
                    "No supported browsers found.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else {
                Text(
                    "Installed & Monitored: ${installedBrowsers.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomBrowserDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit
) {
    if (!showDialog) return
    
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = getLauncherApps(context)
        isLoading = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Select Custom Browser",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )
            
            Text(
                text = "Reef's Accessibility AI will dynamically lock onto the URL bar for the selected app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(apps, key = { it.packageName }) { app ->
                        ListItem(
                            modifier = Modifier.clickable {
                                val currentSet = prefs.getStringSet("custom_browsers", emptySet())?.toMutableSet() ?: mutableSetOf()
                                currentSet.add(app.packageName)
                                prefs.edit().putStringSet("custom_browsers", currentSet).apply()
                                onDismiss()
                            },
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName) },
                            leadingContent = {
                                app.icon?.let { drawable ->
                                    Image(
                                        bitmap = drawable.toBitmap(120, 120).asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
