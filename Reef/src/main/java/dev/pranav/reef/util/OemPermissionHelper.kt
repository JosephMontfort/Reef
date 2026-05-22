package dev.pranav.reef.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.pranav.reef.R

// ──────────────────────────────────────────────────────────────────────────────
// OEM detection
// ──────────────────────────────────────────────────────────────────────────────

enum class OemFamily { XIAOMI, OPPO_REALME_ONEPLUS, VIVO, HUAWEI_HONOR, STOCK }

fun detectOem(): OemFamily {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    return when {
        manufacturer.contains("xiaomi") || brand.contains("xiaomi") ||
                brand.contains("redmi") || brand.contains("poco") -> OemFamily.XIAOMI

        manufacturer.contains("oppo") || brand.contains("oppo") ||
                brand.contains("realme") || manufacturer.contains("oneplus") ||
                brand.contains("oneplus") -> OemFamily.OPPO_REALME_ONEPLUS

        manufacturer.contains("vivo") || brand.contains("vivo") ||
                brand.contains("iqoo") -> OemFamily.VIVO

        manufacturer.contains("huawei") || brand.contains("huawei") ||
                brand.contains("honor") -> OemFamily.HUAWEI_HONOR

        else -> OemFamily.STOCK
    }
}

/** True if the device is from a Chinese OEM that has proprietary autostart managers. */
fun isChineseOem() = detectOem() != OemFamily.STOCK

// ──────────────────────────────────────────────────────────────────────────────
// Overlay permission
// ──────────────────────────────────────────────────────────────────────────────

fun Activity.hasOverlayPermission() = Settings.canDrawOverlays(this)

/**
 * Show the correct UI to grant SYSTEM_ALERT_WINDOW.
 * 1. Always opens the standard Android overlay-permission screen.
 * 2. On Chinese OEMs, shows a modal with exact step-by-step instructions
 *    PLUS a button to open the OEM's proprietary settings screen.
 */
fun Activity.requestOverlayPermission(onDone: (() -> Unit)? = null) {
    val standardIntent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:$packageName")
    )

    when (detectOem()) {
        OemFamily.XIAOMI -> showOemOverlayDialog(
            instructions = "Scroll down to the 'Settings' section.\n" +
                    "• Turn on 'Display pop-up windows while running in the background'\n" +
                    "• Turn on 'Show on Lock screen' → set both to 'Always allow'.",
            deepLinkIntent = buildIntent(
                pkg = "com.miui.securitycenter",
                cls = "com.miui.permcenter.permissions.PermissionsEditorActivity",
                extras = mapOf("extra_pkgname" to packageName)
            ),
            standardIntent = standardIntent,
            onDone = onDone
        )

        OemFamily.OPPO_REALME_ONEPLUS -> showOemOverlayDialog(
            instructions = "Find our app in the list and turn on 'Display over other apps'.",
            deepLinkIntent = buildIntent(
                pkg = "com.coloros.safecenter",
                cls = "com.coloros.safecenter.sysfloatwindow.FloatWindowListActivity"
            ),
            standardIntent = standardIntent,
            onDone = onDone
        )

        OemFamily.VIVO -> showOemOverlayDialog(
            instructions = "Locate the permissions list and enable 'Floating Window' " +
                    "(or 'Display over other apps'), then make sure 'Background Start' is explicitly allowed.",
            deepLinkIntent = buildIntent(
                pkg = "com.vivo.permissionmanager",
                cls = "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                extras = mapOf("packagename" to packageName)
            ),
            standardIntent = standardIntent,
            onDone = onDone
        )

        OemFamily.HUAWEI_HONOR -> showOemOverlayDialog(
            instructions = "Find the 'Draw over other apps' or 'Dropzone' setting and toggle it to 'Allowed'.",
            deepLinkIntent = buildIntent(
                pkg = "com.huawei.systemmanager",
                cls = "com.huawei.systemmanager.addviewmonitor.AddViewMonitorActivity"
            ),
            standardIntent = standardIntent,
            onDone = onDone
        )

        OemFamily.STOCK -> safeStartActivity(standardIntent, onDone)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Autostart permission
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns true if this device does NOT need a special autostart grant —
 * i.e. stock Android (Pixel, Samsung, Motorola) where BOOT_COMPLETED fires reliably.
 */
fun doesNotNeedAutostartGrant() = !isChineseOem()

/**
 * Show the OEM autostart manager. Call only on Chinese OEM devices.
 * Always wraps in a try-catch; falls back to APP_DETAILS if the deep link fails.
 */
fun Activity.requestAutostartPermission(onDone: (() -> Unit)? = null) {
    if (!isChineseOem()) { onDone?.invoke(); return }

    val (instructions, primaryIntent, fallbackIntent) = when (detectOem()) {
        OemFamily.XIAOMI -> Triple(
            "Locate our app in the list and flip the 'Autostart' toggle switch to 'On'.",
            buildIntent("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            null
        )

        OemFamily.OPPO_REALME_ONEPLUS -> Triple(
            "Go to the App Startup / Auto-Launch manager and toggle our app to 'Allowed'.",
            buildIntent("com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            buildIntent("com.oplus.safecenter",
                "com.oplus.safecenter.startupapp.StartupAppListActivity")
        )

        OemFamily.VIVO -> Triple(
            "Look for 'Autostart' or 'Auto-launch' and enable it for our app.",
            buildIntent("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            null
        )

        OemFamily.HUAWEI_HONOR -> Triple(
            "Find our app, turn off 'Manage Automatically', and ensure 'Auto-launch' " +
                    "and 'Secondary Launch' are explicitly checked.",
            buildIntent("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            buildIntent("com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")
        )

        OemFamily.STOCK -> { onDone?.invoke(); return }
    }

    showAutostartDialog(instructions, primaryIntent, fallbackIntent, onDone)
}

// ──────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ──────────────────────────────────────────────────────────────────────────────

private fun Activity.showOemOverlayDialog(
    instructions: String,
    deepLinkIntent: Intent?,
    standardIntent: Intent,
    onDone: (() -> Unit)?
) {
    val dialogBuilder = AlertDialog.Builder(this)
        .setTitle(getString(R.string.overlay_permission))
        .setMessage(
            "This permission allows Reef to display a blocking screen over other apps.\n\n" +
                    "On your device, follow these steps:\n\n$instructions"
        )
        .setNegativeButton(getString(android.R.string.cancel)) { d, _ -> d.dismiss() }

    // Offer the OEM deep link if available and resolvable
    if (deepLinkIntent != null && isIntentResolvable(deepLinkIntent)) {
        dialogBuilder.setPositiveButton("Open ${Build.BRAND} Settings") { _, _ ->
            safeStartActivity(deepLinkIntent) {
                // fallback to standard Android screen
                safeStartActivity(standardIntent, onDone)
            }
        }
    }
    dialogBuilder.setNeutralButton("Open Standard Settings") { _, _ ->
        safeStartActivity(standardIntent, onDone)
    }
    dialogBuilder.show()
}

private fun Activity.showAutostartDialog(
    instructions: String,
    primaryIntent: Intent,
    fallbackIntent: Intent?,
    onDone: (() -> Unit)?
) {
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.autostart_permission))
        .setMessage(
            "Reef needs to start automatically on boot so your focus sessions and app " +
                    "blocking continue working even after a restart.\n\n" +
                    "On your device:\n\n$instructions"
        )
        .setPositiveButton("Open ${Build.BRAND} Settings") { _, _ ->
            val launched = trySafeStartActivity(primaryIntent)
            if (!launched) {
                val launchedFallback = fallbackIntent != null && trySafeStartActivity(fallbackIntent)
                if (!launchedFallback) {
                    safeStartActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }, onDone
                    )
                }
            }
        }
        .setNegativeButton(getString(android.R.string.cancel)) { d, _ -> d.dismiss() }
        .show()
}

private fun Activity.safeStartActivity(intent: Intent, onDone: (() -> Unit)? = null) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // Global fallback: App Details screen
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {}
    }
    onDone?.invoke()
}

private fun Activity.trySafeStartActivity(intent: Intent): Boolean = try {
    startActivity(intent); true
} catch (_: Exception) { false }

private fun buildIntent(
    pkg: String,
    cls: String,
    extras: Map<String, String> = emptyMap()
): Intent = Intent().apply {
    component = ComponentName(pkg, cls)
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
    extras.forEach { (k, v) -> putExtra(k, v) }
}

private fun Activity.isIntentResolvable(intent: Intent): Boolean {
    return try {
        packageManager.resolveActivity(
            intent, PackageManager.MATCH_DEFAULT_ONLY
        ) != null
    } catch (_: Exception) { false }
}
