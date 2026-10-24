package com.storage.redirect.x.data.model

import org.junit.Assert.*
import org.junit.Test

class FileMonitorTest {
    @Test
    fun sharedActorsAreNotAssignedToTheFirstPackage() {
        val entry = FileMonitorEntry.parse(
            """{"version":2,"process":"com.android.providers.media.module","caller":"app.first,app.second","operation":"OPEN","path":"/storage/emulated/0/a","result":0,"method":"fuse_uid","confidence":"high"}"""
        )
        assertEquals("app.first,app.second", entry.actorIdentity)
        assertEquals("shared-android.media", entry.copy(caller = "shared-android.media").actorIdentity)
    }

    private fun entry(
        time: Long,
        operation: String = "WRITE",
        result: Int = 4096,
        caller: String = "app.one",
    ) =
        FileMonitorEntry(
            timestamp = "2026-10-24 12:00:00",
            packageName = "media.provider",
            caller = caller,
            opType = operation,
            path = "/storage/emulated/0/测试|a.txt",
            resultCode = result,
            errorNo = if (result < 0) 13 else 0,
            timeMillis = time,
            bytes = result.coerceAtLeast(0).toLong(),
            extra = "identify_method=caller|identify_reliability=high|op=pwrite|offset=$time",
        )

    @Test
    fun jsonKeepsFileNamesAndLargeResults() {
        val entry =
            FileMonitorEntry.parse(
                """{"version":2,"sequence":77,"time_ms":1792814400000,"process":"media.provider","caller":"app.one","uid":10234,"pid":123,"operation":"WRITE","path":"/storage/emulated/0/a|b\nc\t.jpg","result":4294967296,"errno":0,"method":"caller","confidence":"high","extra":"op=pwrite"}"""
            )
        assertEquals("/storage/emulated/0/a|b\nc\t.jpg", entry.path)
        assertEquals(4294967296L, entry.nativeResult)
        assertEquals(4294967296L, entry.bytes)
        assertEquals(77L, entry.sequence)
        assertEquals(MonitorActionType.WRITE, entry.actionType)
        assertTrue(entry.isSuccess)
    }

    @Test
    fun repeatedFuseOpensAreCountedWithoutCombiningDifferentApps() {
        val values = listOf(entry(10000, operation = "OPEN"), entry(9900, operation = "OPEN"),
            entry(9800, operation = "OPEN", caller = "app.two"))
        val grouped = MonitorAggregation.group(values)
        assertEquals(2, grouped.size)
        assertEquals(2L, grouped.first().count)
        assertEquals("app.two", grouped.last().caller)
    }

    @Test
    fun repeatedReadsAndWritesKeepExactCountsAndBytes() {
        val grouped =
            MonitorAggregation.group(
                listOf(entry(10000), entry(9500, "READ"), entry(9000), entry(8500, "READ"))
            )
        assertEquals(2, grouped.size)
        assertEquals(2L, grouped[0].count)
        assertEquals(8192L, grouped[0].bytes)
        assertEquals(2L, grouped[1].count)
        assertEquals(10000L, grouped[0].lastTimeMillis)
        assertEquals(9000L, grouped[0].timeMillis)
    }

    @Test
    fun timeWindowDoesNotCollapseAnEndlessWriteStream() {
        val grouped = MonitorAggregation.group(listOf(entry(10000), entry(8000), entry(6000)))
        assertEquals(2, grouped.size)
        assertEquals(2L, grouped.first().count)
    }

    @Test
    fun failuresCallersAndProcessesRemainDistinct() {
        val values =
            listOf(
                entry(10000),
                entry(9900, result = -1),
                entry(9800, caller = "app.two"),
                entry(9700).copy(processId = 8),
            )
        assertEquals(4, MonitorAggregation.group(values).size)
        assertFalse(values[1].isSuccess)
    }

    @Test
    fun groupingAcrossPageBoundariesDoesNotLoseRecords() {
        val page1 = listOf(entry(10000), entry(9800))
        val page2 = listOf(entry(9600), entry(6000))
        val grouped = MonitorAggregation.group(page1 + page2)
        assertEquals(4L, grouped.sumOf { it.count })
        assertEquals(16384L, grouped.sumOf { it.bytes })
        assertEquals(3L, grouped.first().count)
        assertEquals(1L, page1.first().count)
    }

    @Test
    fun legacyAndMalformedRecordsRemainSafe() {
        assertEquals(
            MonitorActionType.CREATE,
            FileMonitorEntry.parse(
                    "2026-10-24 12:00:00|app.one|-|CREATE|/storage/emulated/0/a|ret=7|errno=0|op=open|flags=0x40"
                )
                .actionType,
        )
        assertEquals("", FileMonitorEntry.parse("{malformed").path)
    }

    @Test
    fun concurrentlyDeliveredRecordsStillShareOneRow() {
        val grouped = MonitorAggregation.group(listOf(entry(10000), entry(11000), entry(9500)))
        assertEquals(1, grouped.size)
        assertEquals(3L, grouped.single().count)
        assertEquals(9500L, grouped.single().timeMillis)
        assertEquals(11000L, grouped.single().lastTimeMillis)
    }
}
