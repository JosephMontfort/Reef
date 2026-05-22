package dev.pranav.reef.ui.whitelist

import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
    private val _searchQuery = mutableStateOf("")

    val uiState: State<AllowedAppsState> = _uiState
    val searchQuery: State<String> = _searchQuery

    private var allApps = listOf<WhitelistedApp>()

    init { loadApps() }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val profiles = launcherApps.profiles
                val allAppsList = mutableListOf<WhitelistedApp>()

                profiles.forEach { userHandle ->
                    // Use LauncherApps.getActivityList() — this returns exactly the apps
                    // that have a launcher icon the user can tap on the home screen.
                    // Camera, Calculator, system browsers etc. appear here even though
                    // they are system apps. No secondary FLAG_SYSTEM list needed.
                    val launcherActivities = launcherApps
                        .getActivityList(null, userHandle)
                        .distinctBy { it.applicationInfo.packageName }
                        .filter { it.applicationInfo.packageName != currentPackageName }

                    launcherActivities.forEach { info ->
                        val badgedIcon = packageManager.getUserBadgedIcon(
                            info.applicationInfo.loadIcon(packageManager), userHandle
                        )
                        allAppsList.add(
                            WhitelistedApp(
                                packageName = info.applicationInfo.packageName,
                                label = info.applicationInfo
                                    .loadLabel(packageManager).toString(),
                                icon = drawableToBitmap(badgedIcon).asImageBitmap(),
                                isWhitelisted = Whitelist.isWhitelisted(
                                    info.applicationInfo.packageName
                                ),
                                user = userHandle
                            )
                        )
                    }
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

    private fun updateFilteredList() {
        if (allApps.isEmpty() && _uiState.value is AllowedAppsState.Loading) return
        val query = _searchQuery.value
        val filtered = if (query.isEmpty()) allApps
        else allApps.filter {
            it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
        }
        _uiState.value = AllowedAppsState.Success(filtered)
    }

    fun toggleWhitelist(app: WhitelistedApp) {
        if (app.isWhitelisted) Whitelist.unwhitelist(app.packageName)
        else Whitelist.whitelist(app.packageName)
        allApps = allApps.map {
            if (it.packageName == app.packageName && it.user == app.user)
                it.copy(isWhitelisted = !it.isWhitelisted) else it
        }
        updateFilteredList()
    }

    companion object {
        /** Safe Drawable → Bitmap conversion that doesn't require core-ktx. */
        fun drawableToBitmap(drawable: Drawable): Bitmap {
            if (drawable is BitmapDrawable && drawable.bitmap != null) return drawable.bitmap
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }
    }
}

