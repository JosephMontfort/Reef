package dev.pranav.reef.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object OemOverlayManager {

    private var cachedIntent: Intent? = null
    private var isIntentCached = false

    suspend fun init(context: Context) = withContext(Dispatchers.IO) {
        if (!isIntentCached) {
            cachedIntent = findOemOverlayIntent(context)
            isIntentCached = true
        }
    }

    private fun findOemOverlayIntent(context: Context): Intent? {
        val intents = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            },
            Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity")
            },
            Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            },
            Intent("vivo.intent.action.appmanager.router").apply {
                putExtra("fragmentName", "com.vivo.appmanager.fragment.permission.FloatWindowManagerFragment")
            },
            Intent("vivo.intent.action.appmanager.router").apply {
                putExtra("fragmentName", "com.vivo.appmanager.fragment.permission.AppStartBgActivityFragment")
            }
        )

        for (intent in intents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    return intent
                }
            } catch (_: Exception) {}
        }
        return null
    }

    fun getOemOverlayIntent(context: Context): Intent? {
        if (!isIntentCached) {
            cachedIntent = findOemOverlayIntent(context)
            isIntentCached = true
        }
        return cachedIntent
    }

    suspend fun isOemOverlayGrantedAsync(context: Context): Boolean = withContext(Dispatchers.IO) {
        return@withContext isOemOverlayGranted(context)
    }

    fun isOemOverlayGranted(context: Context): Boolean {
        if (getOemOverlayIntent(context) == null) return true

        // Xiaomi MIUI flawless programmatic verification
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.javaClass.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .invoke(appOps, 10021, android.os.Process.myUid(), context.packageName) as Int
            if (mode == AppOpsManager.MODE_ALLOWED) return true
        } catch (_: Exception) { }
        
        // Vivo programmatic verification via hidden ContentProvider
        try {
            val uri = Uri.parse("content://com.vivo.permissionmanager.provider.permission/start_bg_activity")
            context.contentResolver.query(uri, null, "pkgname = ?", arrayOf(context.packageName), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val state = cursor.getInt(cursor.getColumnIndex("currentstate"))
                    if (state == 0) return true
                }
            }
        } catch (_: Exception) { }

        // Fallback for Oppo/Realme or if programmatic methods fail
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("oem_overlay_verified", false)
    }
    
    fun markOemVerified(context: Context, verified: Boolean) {
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("oem_overlay_verified", verified).apply()
    }
}
