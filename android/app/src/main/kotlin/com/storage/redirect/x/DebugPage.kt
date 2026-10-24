package com.storage.redirect.x

// Debug 指令可打开的页面白名单，正式包中该 action 不会被触发
internal enum class DebugPage(val id: String) {
    HOME("home"),
    APPS("apps"),
    MONITOR("monitor"),
    SETTINGS("settings");

    companion object {
        const val ACTION_OPEN_PAGE = "com.storage.redirect.x.debug.OPEN_PAGE"
        const val EXTRA_PAGE = "page"

        fun from(rawValue: String?): DebugPage? {
            val normalized = rawValue?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.id == normalized }
        }
    }
}
