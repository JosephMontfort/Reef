package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREF_WEBSITE_LIMITS = "website_limits"

object WebsiteLimits {
    private lateinit var prefs: SharedPreferences
    private val limits = mutableMapOf<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_WEBSITE_LIMITS, Context.MODE_PRIVATE)
        limits.clear()
        prefs.all.forEach { (k, v) -> if (v is Long) limits[clean(k)] = v }
    }

    private fun clean(url: String): String = url.lowercase().trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    fun setLimit(domain: String, minutes: Int) {
        limits[clean(domain)] = minutes * 60_000L
        save()
    }

    fun getLimit(domain: String): Long = limits[clean(domain)] ?: 0L

    fun hasLimit(domain: String): Boolean = limits.containsKey(clean(domain))

    fun removeLimit(domain: String) {
        limits.remove(clean(domain))
        save()
    }

    fun getDomainsWithLimits(): Map<String, Long> = limits.toMap()

    private fun save() {
        check(::prefs.isInitialized)
        prefs.edit {
            clear()
            limits.forEach { putLong(clean(it.key), it.value) }
        }
    }

    fun resolveDomain(domain: String): String? {
        val searchDomain = clean(domain)
        if (limits.containsKey(searchDomain)) return searchDomain
        for (limited in limits.keys) {
            val cleanLimited = clean(limited)
            if (searchDomain.endsWith(".$cleanLimited") || searchDomain == cleanLimited) return limited
        }
        return null
    }
}
