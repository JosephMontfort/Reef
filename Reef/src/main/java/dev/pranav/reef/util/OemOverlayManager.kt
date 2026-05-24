package dev.pranav.reef.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Locale

object OemOverlayManager {

    fun getOemOverlayIntent(context: Context): Intent? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val intents = mutableListOf<Intent>()

        if (manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") || manufacturer.contains("blackshark")) {
            intents.add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            })
        } else if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
            intents.add(Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity")
            })
            intents.add(Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            })
        } else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            intents.add(Intent("vivo.intent.action.appmanager.router").apply {
                putExtra("fragmentName", "com.vivo.appmanager.fragment.permission.FloatWindowManagerFragment")
            })
        }

        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        return null
    }

    fun isOemOverlayGranted(context: Context): Boolean {
        if (getOemOverlayIntent(context) == null) return true

        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        
        // Auto-detection ONLY for MIUI via AppOps 10021
        if (manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") || manufacturer.contains("blackshark")) {
            try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = appOps.javaClass.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                    .invoke(appOps, 10021, android.os.Process.myUid(), context.packageName) as Int
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // For Oppo/Vivo/Realme, rely strictly on manual user verification state
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("oem_overlay_verified", false)
    }
    
    fun markOemVerified(context: Context, verified: Boolean) {
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("oem_overlay_verified", verified).apply()
    }
}
