package dev.pranav.reef.util

import android.content.Context
import android.util.Base64
import android.util.Log

/**
 * Nuclear watchdog — survives app force-stop via root-level cgroup escape.
 *
 * WHY THE OLD VERSION DIED ON FORCE-STOP
 * ───────────────────────────────────────
 * Android force-stop kills every process in the *app's cgroup*, not just by
 * PID. `nohup` only prevents SIGHUP; it cannot protect against SIGKILL sent
 * to an entire cgroup. The old script was still a member of the app's cgroup
 * because it was spawned from the same `su` shell that was a child of the
 * Java process — so it died with the app every time.
 *
 * HOW THIS VERSION SURVIVES
 * ─────────────────────────
 * 1. Phantom Process Killer disabled via `device_config`.
 * 2. `setsid` creates a new session + process group, detaching from the
 *    app's process group entirely.
 * 3. Script's PID gets oom_score_adj = -1000 (LMK immune).
 * 4. Script PID moved into root memory/freezer cgroup so Android's
 *    cgroup-based force-stop sweep cannot reach it.
 * 5. Script uses `pidof` at 1-second intervals (faster than `pgrep -f`).
 * 6. Script written via Base64 decode — no heredoc quoting issues.
 */
object WatchdogManager {

    private const val TAG = "WatchdogManager"

    private const val SCRIPT_PATH   = "/data/local/tmp/reef_watchdog.sh"
    private const val PID_FILE      = "/data/local/tmp/reef_watchdog.pid"
    private const val SENTINEL_PATH = "/data/local/tmp/reef_watchdog.active"

    // ── Public API ────────────────────────────────────────────────────────────

    fun hasRoot(): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readLine() ?: ""
        p.waitFor()
        out.contains("uid=0")
    }.getOrDefault(false)

    fun start(context: Context) {
        val pkg = context.packageName
        val svcClass = "$pkg.accessibility.AppBlockerService"
        val script   = buildScript(pkg, svcClass)

        // Write script via Base64 — avoids every heredoc quoting pitfall
        val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        runRootCmd("echo '$b64' | base64 -d > $SCRIPT_PATH && chmod 755 $SCRIPT_PATH")

        // Disable Android 12+ Phantom Process Killer
        runRootCmd("device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true")

        // Kill any stale instance
        runRootCmd("[ -f $PID_FILE ] && kill -9 \$(cat $PID_FILE) 2>/dev/null; rm -f $PID_FILE $SENTINEL_PATH; sleep 0.3")

        // Write sentinel
        runRootCmd("echo 1 > $SENTINEL_PATH")

        // Launch: setsid escapes the app's cgroup entirely.
        // The sub-shell writes its own PID before exec-ing the script.
        runRootCmd("setsid sh -c 'echo \$\$ > $PID_FILE; echo -1000 > /proc/\$\$/oom_score_adj 2>/dev/null; exec sh $SCRIPT_PATH' </dev/null >/dev/null 2>&1 &")

        // Give the script 600 ms to write its PID, then protect it from LMK + cgroup kill
        Thread.sleep(600)
        runRootCmd(buildProtectCmd())

        SessionPersistence.markNuclearRunning(context, true)
        Log.i(TAG, "Nuclear watchdog started")
    }

    fun stop(context: Context) {
        runRootCmd("rm -f $SENTINEL_PATH")
        runRootCmd("[ -f $PID_FILE ] && kill -9 \$(cat $PID_FILE) 2>/dev/null; rm -f $PID_FILE")
        runRootCmd("pkill -f reef_watchdog 2>/dev/null || true")
        SessionPersistence.markNuclearRunning(context, false)
        Log.i(TAG, "Nuclear watchdog stopped")
    }

    // ── Script ────────────────────────────────────────────────────────────────

    private fun buildScript(pkg: String, svcClass: String): String {
        // Use raw string with manual dollar escaping so Kotlin doesn't
        // interpolate the shell variables.
        return """#!/system/bin/sh
# Reef Nuclear Watchdog
PKG="${pkg}"
SVC="${svcClass}"
SENTINEL="${SENTINEL_PATH}"

# Protect ourselves from LMK
echo -1000 > /proc/${'$'}${'$'}/oom_score_adj 2>/dev/null

# Re-disable phantom killer in case it reset
device_config put activity_manager max_phantom_processes 2147483647 2>/dev/null || true

while [ -f "${'$'}{SENTINEL}" ]; do
    sleep 1

    # pidof is faster and more precise than pgrep -f
    if ! pidof "${pkg}" > /dev/null 2>&1; then
        if ! pidof "${pkg}:accessibility" > /dev/null 2>&1; then
            am start-foreground-service -n "${'$'}{PKG}/${'$'}{SVC}" \
                --activity-no-animation 2>/dev/null

            # Once app restarts, push its OOM score down too
            sleep 2
            APP_PID=${'$'}(pidof "${pkg}" 2>/dev/null | awk '{print $1}')
            if [ -n "${'$'}{APP_PID}" ]; then
                echo -900 > /proc/${'$'}{APP_PID}/oom_score_adj 2>/dev/null
            fi
        fi
    fi
done
"""
    }

    private fun buildProtectCmd(): String =
        """if [ -f $PID_FILE ]; then
             WPID=$(cat $PID_FILE);
             echo -1000 > /proc/${"\$"}{WPID}/oom_score_adj 2>/dev/null;
             echo ${"\$"}{WPID} > /sys/fs/cgroup/memory/tasks 2>/dev/null;
             echo ${"\$"}{WPID} > /sys/fs/cgroup/freezer/tasks 2>/dev/null;
             echo ${"\$"}{WPID} > /acct/tasks 2>/dev/null;
           fi""".trimMargin()

    // ── Execution ─────────────────────────────────────────────────────────────

    private fun runRootCmd(cmd: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val err = proc.errorStream.bufferedReader().readText().trim()
                if (err.isNotEmpty()) Log.w(TAG, "exit=$exitCode err=$err | cmd=$cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "runRootCmd failed: $cmd", e)
        }
    }
}
