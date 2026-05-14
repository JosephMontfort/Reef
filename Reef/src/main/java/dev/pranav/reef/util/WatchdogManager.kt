package dev.pranav.reef.util

import android.content.Context
import android.util.Log

/**
 * Nuclear watchdog: a root shell script that loops every 5 seconds, checks
 * if Reef's process is alive, and fires `am start-foreground-service` to
 * restart AppBlockerService if not.
 *
 * Requires root (`su`). The script runs as a detached background process
 * (`nohup … &`) so it outlives even a force-stop of the Java app.
 *
 * A sentinel file ([SENTINEL_PATH]) acts as a kill switch: when the file is
 * absent the script exits cleanly, so we never need to hunt for its PID.
 */
object WatchdogManager {

    private const val TAG = "WatchdogManager"

    // ── Paths (inside /data/local/tmp so su can always write them) ────────────
    private const val SCRIPT_PATH   = "/data/local/tmp/reef_watchdog.sh"
    private const val SENTINEL_PATH = "/data/local/tmp/reef_watchdog.active"

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns true if `su` is accessible on this device. */
    fun hasRoot(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
        val out = p.inputStream.bufferedReader().readLine()
        p.waitFor()
        out?.trim() == "ok"
    }.getOrDefault(false)

    /**
     * Installs and starts the watchdog.
     * Safe to call repeatedly — won't start a second copy if already running.
     */
    fun start(context: Context) {
        val pkg = context.packageName
        val svcClass = "$pkg.accessibility.AppBlockerService"

        val script = buildScript(pkg, svcClass)

        runRoot(
            // 1. Write the sentinel so the (possibly already running) script keeps going
            "echo 1 > $SENTINEL_PATH",
            // 2. Write / overwrite the script
            "cat > $SCRIPT_PATH << 'REEF_EOF'\n$script\nREEF_EOF",
            "chmod 755 $SCRIPT_PATH",
            // 3. Kill any previous instance before starting fresh
            "pkill -f reef_watchdog 2>/dev/null; true",
            // 4. Start detached — survives app death
            "nohup sh $SCRIPT_PATH > /dev/null 2>&1 &"
        )
        SessionPersistence.markNuclearRunning(context, true)
        Log.i(TAG, "Watchdog started")
    }

    /** Stops the watchdog by removing the sentinel file. */
    fun stop(context: Context) {
        runRoot(
            "rm -f $SENTINEL_PATH",
            "pkill -f reef_watchdog 2>/dev/null; true"
        )
        SessionPersistence.markNuclearRunning(context, false)
        Log.i(TAG, "Watchdog stopped")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildScript(pkg: String, svcClass: String): String = """
#!/system/bin/sh
# Reef nuclear watchdog — auto-generated, do not edit
PKG="$pkg"
SVC="$svcClass"
SENTINEL="$SENTINEL_PATH"

while [ -f "${'$'}SENTINEL" ]; do
    sleep 5
    # Check if the package process is alive
    if ! pgrep -f "${'$'}PKG" > /dev/null 2>&1; then
        am start-foreground-service -n "${'$'}PKG/${'$'}SVC" > /dev/null 2>&1
    fi
done
""".trimIndent()

    /**
     * Run a series of shell commands sequentially under `su`.
     * Each string is one shell statement; they are joined with `;`.
     */
    private fun runRoot(vararg commands: String) {
        val joined = commands.joinToString("; ")
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", joined))
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $joined", e)
        }
    }
}
