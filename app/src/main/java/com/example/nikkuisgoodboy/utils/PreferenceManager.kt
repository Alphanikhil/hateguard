package com.example.nikkuisgoodboy.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val preferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun setProtectionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    fun isProtectionEnabled(): Boolean {
        return preferences.getBoolean(KEY_PROTECTION_ENABLED, false)
    }

    fun saveDeepSeekApiKey(apiKey: String) {
        preferences.edit().putString(KEY_DEEPSEEK_API_KEY, apiKey).apply()
    }

    fun getDeepSeekApiKey(): String {
        return preferences.getString(KEY_DEEPSEEK_API_KEY, "") ?: ""
    }

    fun setScanningFrequency(frequency: Int) {
        preferences.edit().putInt(KEY_SCANNING_FREQUENCY, frequency).apply()
    }

    fun getScanningFrequency(): Int {
        return preferences.getInt(KEY_SCANNING_FREQUENCY, DEFAULT_SCANNING_FREQUENCY)
    }

    fun setContentFilterLevel(level: Int) {
        preferences.edit().putInt(KEY_CONTENT_FILTER_LEVEL, level).apply()
    }

    fun getContentFilterLevel(): Int {
        return preferences.getInt(KEY_CONTENT_FILTER_LEVEL, DEFAULT_CONTENT_FILTER_LEVEL)
    }

    fun addWhitelistedApp(packageName: String) {
        val whitelistedApps = getWhitelistedApps().toMutableSet()
        whitelistedApps.add(packageName)
        preferences.edit().putStringSet(KEY_WHITELISTED_APPS, whitelistedApps).apply()
    }

    fun removeWhitelistedApp(packageName: String) {
        val whitelistedApps = getWhitelistedApps().toMutableSet()
        whitelistedApps.remove(packageName)
        preferences.edit().putStringSet(KEY_WHITELISTED_APPS, whitelistedApps).apply()
    }

    fun getWhitelistedApps(): Set<String> {
        return preferences.getStringSet(KEY_WHITELISTED_APPS, emptySet()) ?: emptySet()
    }

    fun isAppWhitelisted(packageName: String): Boolean {
        return getWhitelistedApps().contains(packageName)
    }

    companion object {
        private const val PREF_NAME = "hateguard_preferences"
        private const val KEY_PROTECTION_ENABLED = "protection_enabled"
        private const val KEY_DEEPSEEK_API_KEY = "deepseek_api_key"
        private const val KEY_SCANNING_FREQUENCY = "scanning_frequency"
        private const val KEY_CONTENT_FILTER_LEVEL = "content_filter_level"
        private const val KEY_WHITELISTED_APPS = "whitelisted_apps"

        // Default values
        const val DEFAULT_SCANNING_FREQUENCY = 500 // milliseconds
        const val DEFAULT_CONTENT_FILTER_LEVEL = 2 // Medium - 1=Low, 2=Medium, 3=High
    }
}