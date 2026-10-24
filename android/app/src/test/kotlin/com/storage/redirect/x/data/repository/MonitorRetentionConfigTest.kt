package com.storage.redirect.x.data.repository

import org.junit.Assert.*
import org.junit.Test

class MonitorRetentionConfigTest {
    @Test
    fun retentionChangePreservesSwitchesAndUnknownFields() {
        val json =
            ConfigRepository.updateGlobalConfig(
                """{"file_monitor_enabled":false,"fuse_fixer_enabled":true,"future":{"value":42}}""",
                retention = 3600,
            )
        assertFalse(json["file_monitor_enabled"].asBoolean)
        assertTrue(json["fuse_fixer_enabled"].asBoolean)
        assertEquals(3600L, json["file_monitor_retention_seconds"].asLong)
        assertEquals(42, json.getAsJsonObject("future")["value"].asInt)
    }

    @Test
    fun unrelatedSwitchChangeKeepsForeverRetention() {
        val json =
            ConfigRepository.updateGlobalConfig(
                """{"file_monitor_enabled":false,"file_monitor_retention_seconds":0}""",
                fuse = true,
            )
        assertEquals(0L, json["file_monitor_retention_seconds"].asLong)
        assertFalse(json["file_monitor_enabled"].asBoolean)
        assertTrue(json["fuse_fixer_enabled"].asBoolean)
    }
}
