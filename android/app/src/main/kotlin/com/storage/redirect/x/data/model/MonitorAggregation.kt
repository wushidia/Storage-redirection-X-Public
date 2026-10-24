package com.storage.redirect.x.data.model

/** Presentation-only grouping. Raw access records remain intact in the daemon store. */
object MonitorAggregation {
    private const val WINDOW_MS = 3000L
    private val GROUPABLE = setOf("READ", "WRITE", "OPEN", "CREATE", "UPDATE")

    private data class Key(
        val process: String,
        val pid: Int,
        val caller: String,
        val path: String,
        val op: String,
        val error: Int?,
        val success: Boolean,
        val extra: String?,
    )

    fun group(entries: List<FileMonitorEntry>): List<FileMonitorEntry> {
        val result = ArrayList<FileMonitorEntry>(entries.size)
        val indices = HashMap<Key, Int>()
        for (entry in entries) {
            val canGroup = entry.timeMillis > 0 && entry.opType in GROUPABLE
            if (!canGroup) {
                result.add(entry)
                continue
            }
            // Offset can vary between repeated operations. Failures and distinct identities stay
            // separate.
            val key =
                Key(
                    entry.packageName,
                    entry.processId,
                    entry.caller,
                    entry.path,
                    entry.opType,
                    entry.errorNo,
                    entry.isSuccess,
                    entry.extra?.substringBefore("|op="),
                )
            val index = indices[key]
            val previous = index?.let(result::get)
            val first = minOf(previous?.timeMillis ?: entry.timeMillis, entry.timeMillis)
            val last = maxOf(previous?.lastTimeMillis ?: entry.lastTimeMillis, entry.lastTimeMillis)
            if (previous != null && last - first <= WINDOW_MS) {
                val latest = if (entry.lastTimeMillis > previous.lastTimeMillis) entry else previous
                result[index] =
                    latest.copy(
                        sequence = maxOf(previous.sequence, entry.sequence),
                        timeMillis = first,
                        lastTimeMillis = last,
                        count = previous.count + entry.count,
                        bytes = previous.bytes + entry.bytes,
                        lostRecords = previous.lostRecords + entry.lostRecords,
                    )
            } else {
                indices[key] = result.size
                result.add(entry)
            }
        }
        return result
    }
}
