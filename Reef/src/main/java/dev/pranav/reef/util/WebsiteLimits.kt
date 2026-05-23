package dev.pranav.reef.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREF_WEBSITE_LIMITS = "website_limits"

object WebsiteLimits {
    private lateinit var prefs: SharedPreferences
    private val limits = mutableMapOf<String, Long>() // domain to limit in ms

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_WEBSITE_LIMITS, Context.MODE_PRIVATE)
        limits.clear()
        prefs.all.forEach { (k, v) ->
            if (v is Long) limits[k] = v
        
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}
    
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}

    fun setLimit(domain: String, minutes: Int) {
        val limitMs = minutes * 60_000L
        limits[domain] = limitMs
        save()
    
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}

    fun getLimit(domain: String): Long = limits[domain] ?: 0L

    fun hasLimit(domain: String): Boolean = limits.containsKey(domain)

    fun removeLimit(domain: String) {
        limits.remove(domain)
        save()
    
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}

    fun getDomainsWithLimits(): Map<String, Long> = limits.toMap()

    private fun save() {
        check(::prefs.isInitialized)
        prefs.edit {
            clear()
            limits.forEach { putLong(it.key, it.value) 
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}
        
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}
    
    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}

    fun resolveDomain(domain: String): String? {
        if (sharedPreferences.contains(domain)) return domain
        for (limited in sharedPreferences.all.keys) {
            if (domain.endsWith(".$limited") || domain == limited) return limited
        }
        return null
    }
}

