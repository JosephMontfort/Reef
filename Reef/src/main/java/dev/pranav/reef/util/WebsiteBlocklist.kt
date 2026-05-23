package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object WebsiteBlocklist {
    private lateinit var sharedPreferences: SharedPreferences

    fun init(context: Context) {
        sharedPreferences = context.getSharedPreferences("website_blocklist", Context.MODE_PRIVATE)
    }

    private fun clean(url: String): String = url.lowercase().trim()
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/')

    fun isBlocked(domain: String): Boolean = sharedPreferences.getBoolean(clean(domain), false)

    fun addDomain(domain: String) = sharedPreferences.edit { putBoolean(clean(domain), true) }

    fun removeDomain(domain: String) = sharedPreferences.edit { remove(clean(domain)) }

    fun getBlockedDomains(): Set<String> = sharedPreferences.all.keys

    fun resolveDomain(domain: String): String? {
        val searchDomain = clean(domain)
        if (isBlocked(searchDomain)) return searchDomain
        for (blocked in getBlockedDomains()) {
            val cleanBlocked = clean(blocked)
            if (searchDomain.endsWith(".$cleanBlocked") || searchDomain == cleanBlocked) return blocked
        }
        return null
    }
}
