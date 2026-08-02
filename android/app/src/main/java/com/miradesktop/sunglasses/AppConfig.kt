package com.miradesktop.sunglasses

import android.content.Context
import android.graphics.Color

object AppConfig {
    const val DEFAULT_OPACITY = 45
    const val CONFIRM_THRESHOLD = 60
    private const val PREFS = "android_glasses"
    private const val LEGACY_PREFS = "sun" + "glasses"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_COLOR = "color"
    private const val KEY_COVER_SYSTEM_BARS = "cover_system_bars"
    private const val KEY_HIDE_FROM_RECENTS = "hide_from_recents"
    private const val KEY_TOTAL_USAGE_SECONDS = "total_usage_seconds"
    private const val KEY_USAGE_STARTED_AT = "usage_started_at"

    private fun prefs(context: Context): android.content.SharedPreferences {
        val current = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (current.all.isNotEmpty()) return current

        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (legacy.all.isEmpty()) return current
        val editor = current.edit()
        for ((key, value) in legacy.all) {
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is String -> editor.putString(key, value)
            }
        }
        editor.apply()
        return current
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, false)
    fun opacity(context: Context) = prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)
    fun color(context: Context) = normalizeColor(prefs(context).getString(KEY_COLOR, "#000000")) ?: "#000000"
    fun coverSystemBars(context: Context) = prefs(context).getBoolean(KEY_COVER_SYSTEM_BARS, false)
    fun hideFromRecents(context: Context) = prefs(context).getBoolean(KEY_HIDE_FROM_RECENTS, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled == isEnabled(context)) return
        if (enabled) {
            prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                .putLong(KEY_USAGE_STARTED_AT, System.currentTimeMillis())
                .apply()
        } else {
            flushUsage(context)
            prefs(context).edit()
                .putBoolean(KEY_ENABLED, false)
                .remove(KEY_USAGE_STARTED_AT)
                .apply()
        }
    }

    fun setOpacity(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_OPACITY, value.coerceIn(0, 100)).apply()
    }

    fun setColor(context: Context, value: String) {
        normalizeColor(value)?.let { prefs(context).edit().putString(KEY_COLOR, it).apply() }
    }

    fun setCoverSystemBars(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_COVER_SYSTEM_BARS, enabled).apply()
    }

    fun setHideFromRecents(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_FROM_RECENTS, enabled).apply()
    }

    fun currentUsageSeconds(context: Context): Long {
        val stored = prefs(context).getLong(KEY_TOTAL_USAGE_SECONDS, 0L).coerceAtLeast(0L)
        val startedAt = prefs(context).getLong(KEY_USAGE_STARTED_AT, 0L)
        val active = if (isEnabled(context) && startedAt > 0L) {
            ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        } else 0L
        return stored + active
    }

    fun flushUsage(context: Context) {
        val startedAt = prefs(context).getLong(KEY_USAGE_STARTED_AT, 0L)
        if (startedAt <= 0L) return
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        val stored = prefs(context).getLong(KEY_TOTAL_USAGE_SECONDS, 0L).coerceAtLeast(0L)
        prefs(context).edit()
            .putLong(KEY_TOTAL_USAGE_SECONDS, stored + elapsed)
            .putLong(KEY_USAGE_STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun overlayColor(context: Context, opacityOverride: Int? = null): Int {
        val rgb = Color.parseColor(color(context))
        val alpha = ((opacityOverride ?: opacity(context)).coerceIn(0, 100) * 255 / 100)
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb))
    }

    fun normalizeColor(raw: String?): String? {
        val value = raw?.trim()?.uppercase() ?: return null
        if (!Regex("^#[0-9A-F]{6}$").matches(value)) return null
        return value
    }
}
