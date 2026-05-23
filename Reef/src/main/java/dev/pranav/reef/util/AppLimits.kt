package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.time.LocalDate
import java.time.ZoneId

private const val PREF_LIMITS = "app_limits"

object AppLimits {

    private lateinit var prefs: SharedPreferences
    private val limits = mutableMapOf<String, Long>()

    private val reminderSent = mutableMapOf<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_LIMITS, Context.MODE_PRIVATE)
        limits.clear()
        prefs.all.forEach { (k, v) ->
            if (v is Long) limits[k] = v
        }
    }

    fun setLimit(pkg: String, minutes: Int) {
        limits[pkg] = minutes * 60_000L
    }

    fun getLimit(pkg: String): Long = limits[pkg] ?: 0L

    fun hasLimit(pkg: String): Boolean = limits.containsKey(pkg)

    fun removeLimit(pkg: String) {
        limits.remove(pkg)
    }

    fun save() {
        check(::prefs.isInitialized)
        prefs.edit {
            clear()
            limits.forEach { putLong(it.key, it.value) }
        }
    }

    private fun startOfToday(): Long =
        LocalDate.now()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    fun reminderSentToday(pkg: String): Boolean =
        reminderSent[pkg]?.let { it >= startOfToday() } ?: false

    fun markReminder(pkg: String) {
        reminderSent[pkg] = System.currentTimeMillis()
    }
}


object Whitelist {
    private lateinit var sharedPreferences: SharedPreferences

    /** The app's own package name — always treated as whitelisted regardless of prefs. */
    private var ownPackageName: String = ""

    fun init(context: Context) {
        ownPackageName = context.packageName
        sharedPreferences = context.getSharedPreferences("whitelist", Context.MODE_PRIVATE)

        if (sharedPreferences.all.isEmpty()) {
            // Auto-whitelist only genuinely productive/study apps that are installed.
            // We do NOT bulk-whitelist by FLAG_SYSTEM — that whitelists social/entertainment
            // apps that users want to block. Only functional system roles (keyboard, phone,
            // SMS, launcher) and a curated productivity list are whitelisted.
            val pm = context.packageManager
            productiveApps.forEach { pkg ->
                if (isPackageInstalled(pm, pkg)) whitelist(pkg)
            }
        }

        // Always whitelist functional system roles (required for the phone to work normally)

        // Keyboards — blocking a keyboard makes the device unusable
        val inputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        inputMethodManager.enabledInputMethodList.forEach { whitelist(it.packageName) }

        // Default SMS app
        android.provider.Telephony.Sms.getDefaultSmsPackage(context)?.let { whitelist(it) }

        // Default Phone/Dialer app
        (context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager)
            .defaultDialerPackage?.let { whitelist(it) }

        // Default assistant
        context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_ASSIST).apply {
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }, 0
        )?.activityInfo?.packageName?.let { whitelist(it) }

        // Default launcher — must be whitelisted so the home button always works
        context.packageManager.resolveActivity(
            android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
            }, 0
        )?.activityInfo?.packageName?.let { whitelist(it) }

        // NOTE: We intentionally do NOT whitelist by SYSTEM_ALERT_WINDOW permission.
        // Social/entertainment apps commonly hold this and are exactly what users block.
    }

    private fun isPackageInstalled(pm: android.content.pm.PackageManager, pkg: String): Boolean =
        try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }

    /**
     * Curated list of productive and study apps to whitelist on fresh install.
     * Only apps that are actually installed will be whitelisted.
     * Update this list as new popular study/productivity apps emerge.
     */
    val productiveApps = hashSetOf(
        "dev.pranav.reef",
        "com.google.android.googlequicksearchbox", // Core Google App
        "com.openai.chatgpt",                      // ChatGPT
        "com.google.android.apps.gemini",          // Google Gemini
        "com.anthropic.claude",                    // Claude
        "com.microsoft.copilot",                   // Copilot
        "com.perplexity.app",                      // Perplexity AI
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editor.docs",
        "com.google.android.apps.docs.editor.sheets",
        "com.google.android.apps.sheets",
        "com.google.android.apps.slides",
        "com.google.android.apps.drive",
        "com.google.android.keep",
        "com.google.android.apps.tasks",
        "com.google.android.calendar",
        "com.google.android.deskclock",
        "com.google.android.apps.classroom",
        "com.google.android.apps.meet",            // Kept Meet, removed Duo
        "com.google.android.gm",
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.office.onenote",
        "com.microsoft.todos",
        "com.microsoft.teams",
        "org.khanacademy.android",
        "com.duolingo",
        "com.coursera.android",
        "com.edx.mobile",
        "com.udemy.android",
        "com.byju.learning",
        "com.vedantu",
        "com.unacademy",
        "com.sololearn",
        "com.quizlet.quizletandroid",
        "com.ankidroid.anki",
        "md.obsidian",
        "com.notion.id",
        "com.evernote",
        "com.google.android.calculator",
        "com.google.android.apps.maps"
    ) ─────────────────────
        "com.openai.chatgpt",                // ChatGPT
        "com.google.android.apps.bard",      // Google Bard (old package)
        "com.google.android.apps.gemini",    // Google Gemini
        "com.anthropic.claude",              // Claude
        "com.microsoft.copilot",             // Microsoft Copilot
        "com.perplexity.app",                // Perplexity AI

        // ── Google productivity suite ─────────────────────────────────────
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.editor.docs",
        "com.google.android.apps.docs.editor.sheets",
        "com.google.android.apps.sheets",
        "com.google.android.apps.slides",
        "com.google.android.apps.drive",
        "com.google.android.keep",           // Google Keep
        "com.google.android.apps.tasks",
        "com.google.android.apps.paidtasks",
        "com.google.android.calendar",
        "com.google.android.deskclock",      // Clock / Alarms
        "com.google.android.apps.classroom", // Google Classroom
        "com.google.android.apps.meet",      // Google Meet
        "com.google.android.gm",             // Gmail
        "com.google.android.apps.tachyon",   // Google Duo / Meet legacy

        // ── Photos & media pickers (needed by ChatGPT, Gemini etc.) ──────
        "com.google.android.apps.photos",
        "com.google.android.apps.photosgo",
        "com.miui.gallery",                  // Xiaomi Gallery
        "com.sec.android.gallery3d",         // Samsung Gallery
        "com.coloros.gallery3d",             // OPPO/Realme Gallery
        "com.bbk.album",                     // Vivo Album
        "com.huawei.photos",                 // Huawei Gallery
        "com.android.gallery3d",             // AOSP Gallery
        // Media picker provider packages that appear when apps request images/videos
        "com.google.android.providers.media.module",
        "com.android.providers.media",
        "com.android.providers.media.module",

        // ── Microsoft Office / Teams ──────────────────────────────────────
        "com.microsoft.office.word",
        "com.microsoft.office.excel",
        "com.microsoft.office.powerpoint",
        "com.microsoft.office.onenote",
        "com.microsoft.todos",
        "com.microsoft.teams",
        "com.microsoft.launcher.enterprise",

        // ── Education / E-learning ────────────────────────────────────────
        "org.khanacademy.android",           // Khan Academy
        "com.duolingo",                      // Duolingo
        "com.coursera.android",              // Coursera
        "com.edx.mobile",                    // edX
        "com.udemy.android",                 // Udemy
        "com.byju.learning",                 // BYJU'S
        "in.bhanzu.android",                 // Bhanzu
        "com.vedantu",                        // Vedantu
        "com.unacademy",                     // Unacademy
        "com.meritnation",                   // Merit Nation
        "com.toppr",                          // Toppr
        "com.doubtnut.app",                  // Doubtnut
        "io.codelearn.codelearnapp",         // CodeLearn
        "com.sololearn",                     // SoloLearn
        "com.mimo.android",                  // Mimo (coding)
        "com.grasshopper.android",           // Grasshopper (Google coding)
        "com.quizlet.quizletandroid",        // Quizlet
        "com.ankidroid.anki",                // AnkiDroid flashcards
        "com.memrise.android.memrisecompanion", // Memrise

        // ── Note-taking / writing ─────────────────────────────────────────
        "md.obsidian",                       // Obsidian
        "com.notion.id",                     // Notion
        "com.evernote",                      // Evernote
        "com.microsoft.office.onenote",
        "net.cozic.joplin",                  // Joplin
        "org.standardnotes",                 // Standard Notes
        "com.simplenote.android",            // Simplenote

        // ── Focus / study tools ───────────────────────────────────────────
        "com.tiknil.focusflow",              // Focus Flow
        "com.flora.focus",                   // Flora
        "com.brilliantapp",                  // Brilliant

        // ── Communication (work) ──────────────────────────────────────────
        "com.slack",

        // ── Calculators ──────────────────────────────────────────────────
        "com.sadellie.calculator",
        "com.google.android.calculator",
        "com.sec.android.app.popupcalculator", // Samsung calc

        // ── Authentication ────────────────────────────────────────────────
        "com.google.android.apps.authenticator2",
        "me.jmh.authenticatorpro",
        "com.authy.authy",

        // ── Maps / navigation (practical utility) ─────────────────────────
        "com.google.android.apps.maps",
        "net.osmand",

        // ── Camera apps (system) — appear in launcher but are system apps ─
        "com.google.android.GoogleCamera",
        "com.android.camera2",
        "com.sec.android.app.camera",        // Samsung camera
        "com.miui.camera",                   // MIUI camera
        "com.coloros.camera",                // OPPO camera
        "com.vivo.camera",                   // Vivo camera
        "com.huawei.camera",                 // Huawei camera
        "com.lineageos.aperture",
        "com.lineageos.aperture.dev",

        // ── MicroG / alternative frameworks ──────────────────────────────
        "app.revanced.android.gms",
    )

    fun isWhitelisted(packageName: String): Boolean {
        // Reef itself is always allowed — regardless of user prefs
        if (packageName == ownPackageName) return true
        return sharedPreferences.getBoolean(packageName, false)
    }

    fun whitelist(packageName: String) {
        sharedPreferences.edit { putBoolean(packageName, true) }
    }

    fun whitelistAll(set: Set<String>) {
        set.forEach { whitelist(it) }
    }

    fun unwhitelist(packageName: String) {
        sharedPreferences.edit { putBoolean(packageName, false) }
    }

    fun getWhitelistedLaunchableCount(launcherApps: android.content.pm.LauncherApps): Int {
        val launchablePackages =
            launcherApps.getActivityList(null, android.os.Process.myUserHandle())
                .map { it.applicationInfo.packageName }
                .toSet()
        return sharedPreferences.all.count { (pkg, isWhitelisted) ->
            isWhitelisted == true && launchablePackages.contains(pkg)
        }
    }

}
