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

        // Xiaomi / Redmi / Poco / BlackShark
        if (manufacturer.contains("xiaomi") || manufacturer.contains("poco") || manufacturer.contains("redmi") || manufacturer.contains("blackshark")) {
            intents.add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            })
        } 
        // Oppo / Realme / OnePlus
        else if (manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus")) {
            intents.add(Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity")
            })
            intents.add(Intent().apply {
                setClassName("com.coloros.safecenter", "com.coloros.privacypermissionsentry.PermissionTopActivity")
            })
        } 
        // Vivo / iQOO
        else if (manufacturer.contains("vivo") || manufacturer.contains("iqoo")) {
            intents.add(Intent("vivo.intent.action.appmanager.router").apply {
                putExtra("fragmentName", "com.vivo.appmanager.fragment.permission.FloatWindowManagerFragment")
            })
        }

        // Return the first intent that actually exists on this specific device
        for (intent in intents) {
            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            }
        }
        return null
    }

    fun isOemOverlayGranted(context: Context): Boolean {
        if (getOemOverlayIntent(context) == null) return true // No OEM intent needed, inherently "granted"

        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        
        // ALGORITHM: Use reflection to check hidden AppOp 10021 for MIUI background popups
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
        
        // Fallback for ColorOS/OxygenOS where reflection isn't universally mapped
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("oem_overlay_interacted", false)
    }
    
    fun markOemInteracted(context: Context) {
        val prefs = context.getSharedPreferences("reef_intro_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("oem_overlay_interacted", true).apply()
    }
}
