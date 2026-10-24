package com.storage.redirect.x.data.repository

import com.google.gson.JsonParser
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.service.RootService
import com.storage.redirect.x.util.Paths
import kotlinx.collections.immutable.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@androidx.compose.runtime.Immutable
data class MonitorPage(
    val entries: ImmutableList<FileMonitorEntry> = persistentListOf(),
    val hasMore: Boolean = false,
    val lost: Long = 0,
    val writeErrors: Long = 0,
    val legacy: Boolean = false,
    val daemonUpdatedMs: Long = 0,
    val isMonitoringEnabled: Boolean = true,
)

open class FileMonitorRepository {
    // One bounded page, not a whole-file read. The daemon cursor covers all retained segments.
    open suspend fun loadPage(before: Long = 0, after: Long = 0): MonitorPage? =
        withContext(Dispatchers.IO) { readPage(before, after) }

    private suspend fun readPage(before: Long, after: Long): MonitorPage? {
        val command =
            "for b in ${Paths.MODULE_DIR}/bin/*/srx_logd; do " +
                "[ -x \"\$b\" ] || continue; \"\$b\" monitor-query ${before.coerceAtLeast(0)} ${after.coerceAtLeast(0)} 500 2>/dev/null && exit 0; done; exit 1"
        val result = RootService.runCommand(command)
        if (result.isSuccess) {
            val lines = result.stdout.lineSequence().filter { it.isNotBlank() }.toList()
            val header = runCatching {
                JsonParser.parseString(lines.firstOrNull()).asJsonObject
            }.getOrNull()
            if (header?.get("version")?.asInt == 2) {
                fun number(key: String) =
                    header.get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L
                return MonitorPage(
                    entries =
                        lines
                            .drop(1)
                            .map(FileMonitorEntry::parse)
                            .filter(::inScope)
                            .toImmutableList(),
                    hasMore = header.get("has_more")?.asBoolean ?: false,
                    lost = number("lost"),
                    writeErrors = number("write_errors"),
                    daemonUpdatedMs = number("updated_ms"),
                    isMonitoringEnabled = header.get("enabled")?.asBoolean ?: true,
                )
            }
        }
        // Compatibility with old installed modules. Never pretend they provide read/write coverage.
        if (before != 0L || after != 0L) return null
        val legacy = RootService.runCommand("tail -n 500 ${Paths.FILE_MONITOR_LOG} 2>/dev/null")
        if (!legacy.isSuccess) return null
        return MonitorPage(
            legacy.stdout
                .lineSequence()
                .map(FileMonitorEntry::parse)
                .filter(::inScope)
                .toList()
                .asReversed()
                .toImmutableList(),
            legacy = true,
        )
    }

    suspend fun loadEntries(lineCount: Int): ImmutableList<FileMonitorEntry>? = loadPage()?.entries

    open suspend fun setMonitoringEnabled(enabled: Boolean): Boolean = ConfigRepository().setFileMonitorEnabled(enabled)

    suspend fun clear(): Boolean =
        RootService.runCommand(
                "for b in ${Paths.MODULE_DIR}/bin/*/srx_logd; do [ -x \"\$b\" ] || continue; \"\$b\" control clear-monitor && exit 0; done; exit 1"
            )
            .isSuccess

    private fun inScope(entry: FileMonitorEntry): Boolean =
        entry.path == "/storage/emulated/0" || entry.path.startsWith("/storage/emulated/0/")
}
