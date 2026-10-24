package com.storage.redirect.x.debug

import android.content.Context
import android.os.Bundle
import com.google.gson.Gson
import com.storage.redirect.x.data.repository.FileMonitorRepository
import kotlinx.coroutines.runBlocking

// 读取最近 N 条文件访问记录，JSON 数组返回
internal fun Context.runMonitorRecentCommand(extras: Bundle?): Bundle = runDebugCommand(METHOD_MONITOR_RECENT, null) {
    val limit = extras.optionalInt(EXTRA_LIMIT, 20).coerceIn(1, MAX_LIST_ROWS)
    val entries = runBlocking { FileMonitorRepository().loadEntries(limit) }
        ?: return@runDebugCommand debugResult(
            isOk = false,
            message = "Monitor log unavailable (root required)",
            command = METHOD_MONITOR_RECENT,
        )

    debugResult(
        isOk = true,
        message = "rows=${entries.size}",
        command = METHOD_MONITOR_RECENT,
        value = Gson().toJson(entries),
    )
}
