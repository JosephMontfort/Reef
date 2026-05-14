package dev.pranav.reef.util

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class AllowedAppInfo(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap
)

/**
 * Singleton cache for whitelisted-app icons loaded from PackageManager.
 *
 * Pre-warm via [refresh] when a focus session starts (or whenever the
 * whitelist changes) so the overlay screen appears instantly.
 */
object WhitelistAppCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: List<AllowedAppInfo> = emptyList()

    val apps: List<AllowedAppInfo> get() = cache

    /** Loads (or re-loads) the allowed app list in a background coroutine. */
    fun refresh(context: Context) {
        scope.launch {
            cache = load(context)
        }
    }

    /** Blocking load — use only when you can tolerate a short delay. */
    fun refreshSync(context: Context) {
        cache = load(context)
    }

    private fun load(context: Context): List<AllowedAppInfo> {
        return try {
            val pm = context.packageManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            launcherApps
                .getActivityList(null, Process.myUserHandle())
                .distinctBy { it.applicationInfo.packageName }
                .mapNotNull { info ->
                    val pkg = info.applicationInfo.packageName
                    if (!Whitelist.isWhitelisted(pkg)) return@mapNotNull null
                    runCatching {
                        val icon = info.applicationInfo.loadIcon(pm)
                            .toBitmap().asImageBitmap()
                        AllowedAppInfo(pkg, info.label.toString(), icon)
                    }.getOrNull()
                }
                .sortedBy { it.label }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
