package dev.pranav.reef.ui.whitelist

import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pranav.reef.util.Whitelist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WhitelistViewModel(
    private val launcherApps: LauncherApps,
    private val packageManager: PackageManager,
    private val currentPackageName: String
) : ViewModel() {

    private val _uiState = mutableStateOf<AllowedAppsState>(AllowedAppsState.Loading)
    private var allApps = listOf<WhitelistedApp>()

    private val _searchQuery = mutableStateOf("")
    val searchQuery: State<String> = _searchQuery

    private val _hideSystemApps = mutableStateOf(false)
    val hideSystemApps: State<Boolean> = _hideSystemApps

    val uiState: State<AllowedAppsState> = _uiState

    init {
        loadApps()
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val profiles = launcherApps.profiles
                val allAppsList = mutableListOf<WhitelistedApp>()

                profiles.forEach { userHandle ->
                    val launcherActivities = launcherApps.getActivityList(null, userHandle)
                        .distinctBy { it.applicationInfo.packageName }

                    val profileSystemApps =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            launcherApps.getPreInstalledSystemPackages(userHandle)
                                .mapNotNull { pkg ->
                                    runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
                                }
                        } else emptyList()

                    val combined = (launcherActivities.map { it.applicationInfo } + profileSystemApps)
                        .distinctBy { it.packageName }
                        .filter { it.packageName != currentPackageName }
                        .map { appInfo ->
                            val originalIcon = appInfo.loadIcon(packageManager)
                            val badgedIcon = packageManager.getUserBadgedIcon(originalIcon, userHandle)
                            WhitelistedApp(
                                packageName = appInfo.packageName,
                                label       = appInfo.loadLabel(packageManager).toString(),
                                icon        = badgedIcon.toBitmap().asImageBitmap(),
                                isWhitelisted = Whitelist.isWhitelisted(appInfo.packageName),
                                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                user        = userHandle
                            )
                        }
                    allAppsList.addAll(combined)
                }
                allAppsList.sortedBy { it.label }
            }
            allApps = apps
            updateFilteredList()
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        updateFilteredList()
    }

    fun onHideSystemAppsChange(hide: Boolean) {
        _hideSystemApps.value = hide
        updateFilteredList()
    }

    private fun updateFilteredList() {
        val query  = _searchQuery.value
        val hideSystem = _hideSystemApps.value
        val filtered = allApps
            .filter { app -> !hideSystem || !app.isSystemApp }
            .filter { app ->
                query.isEmpty() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            }
        _uiState.value = AllowedAppsState.Success(filtered)
    }

    fun toggleWhitelist(app: WhitelistedApp) {
        if (app.isWhitelisted) Whitelist.unwhitelist(app.packageName)
        else Whitelist.whitelist(app.packageName)

        allApps = allApps.map {
            if (it.packageName == app.packageName && it.user == app.user) it.copy(isWhitelisted = !it.isWhitelisted)
            else it
        }
        updateFilteredList()
    }
}
