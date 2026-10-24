package com.storage.redirect.x.data.cache

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

// 轻量持久化工具，在 Activity 启动时初始化
// 用于缓存设备信息和权限状态，避免每次启动重新加载导致 UI 闪烁
object AppPreferences {
    private const val PREFS_NAME = "app_cache"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getString(key: String, default: String = ""): String {
        return prefs.getString(key, default) ?: default
    }

    fun getInt(key: String, default: Int = 0): Int {
        return prefs.getInt(key, default)
    }

    // 生效次数旧缓存是 Int，升级后保留原值并使用 Long。
    fun getLong(key: String, default: Long = 0L): Long =
        when (val value = prefs.all[key]) {
            is Long -> value
            is Int -> value.toLong()
            else -> default
        }

    fun getBoolean(key: String, default: Boolean = false): Boolean {
        return prefs.getBoolean(key, default)
    }

    fun getFloat(key: String, default: Float = 0f): Float {
        return prefs.getFloat(key, default)
    }

    fun put(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    fun put(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    fun put(key: String, value: Long) {
        prefs.edit { putLong(key, value) }
    }

    fun put(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    fun put(key: String, value: Float) {
        prefs.edit { putFloat(key, value) }
    }
}
