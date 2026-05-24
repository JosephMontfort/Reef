package dev.pranav.reef.ui.blocklist

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pranav.reef.R
import dev.pranav.reef.util.prefs

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
    val customSet = prefs.getStringSet("custom_browsers", emptySet()) ?: emptySet()
    val allBrowsers = defaultBrowserConfigs.keys + customSet.map { it.split(";;")[0] }

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
                    "No supported browsers found. Blocking won't work.",
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
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pkgName by remember { mutableStateOf("") }
    var urlBarId by remember { mutableStateOf("") }
    var suggestionBoxId by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Add Custom Browser",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            
            Text(
                text = "Provide Accessibility View IDs for advanced blocking. (e.g. com.example.browser:id/url_bar)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = pkgName,
                onValueChange = { pkgName = it },
                singleLine = true,
                label = { Text("Package Name (e.g. com.android.chrome)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            OutlinedTextField(
                value = urlBarId,
                onValueChange = { urlBarId = it },
                singleLine = true,
                label = { Text("URL Bar ID") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            OutlinedTextField(
                value = suggestionBoxId,
                onValueChange = { suggestionBoxId = it },
                singleLine = true,
                label = { Text("Suggestion Box ID (Optional)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (pkgName.isNotBlank() && urlBarId.isNotBlank()) {
                            val currentSet = prefs.getStringSet("custom_browsers", emptySet())?.toMutableSet() ?: mutableSetOf()
                            val sugId = if (suggestionBoxId.isNotBlank()) suggestionBoxId else urlBarId
                            currentSet.add("${pkgName.trim()};;${urlBarId.trim()};;${sugId.trim()};;false;;0")
                            prefs.edit().putStringSet("custom_browsers", currentSet).apply()
                            onDismiss()
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}
