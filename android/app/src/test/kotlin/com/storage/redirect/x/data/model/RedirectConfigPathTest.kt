package com.storage.redirect.x.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RedirectConfigPathTest {
    @Test
    fun advancedRulesRoundTripWithoutLosingModeOrExclusions() {
        val source = AppRedirectConfig("example.app", isMappingModeOnly = true,
            sandboxedPaths = listOf("Cache", "!Cache/public"),
            readOnlyPaths = listOf("Pictures", "!Pictures/import"),
            pathMappings = listOf(StoragePathMapping("/data/user/10/example.app/files/*", "Pictures/*")))
        assertEquals(source, AppRedirectConfig.fromUserJson(source.packageName, source.toUserJson()))
        val config = RedirectConfig(userId = 10).updateAppConfig(source)
        assertEquals(10, config.userId)
        assertEquals(source.readOnlyPaths.sorted(), config.getAppConfig(source.packageName)?.readOnlyPaths)
        assertTrue(config.getAppConfig(source.packageName)?.isMappingModeOnly == true)
    }

    @Test
    fun legacyProfilesKeepFullIsolationAndNoExtraRules() {
        val profile = AppRedirectConfig.fromUserJson("example.app",
            com.google.gson.JsonParser.parseString("{\"enabled\":false}").asJsonObject)
        assertFalse(profile.isMappingModeOnly)
        assertTrue(profile.readOnlyPaths.isEmpty())
        assertTrue(profile.sandboxedPaths.isEmpty())
        assertFalse(profile.isEnabled)
    }

    @Test
    fun mappingsAcceptPrivateNamespacesAndCapturedWildcards() {
        assertEquals(PathValidationResult.Valid("/data/user/10/example.app/files/*"),
            RedirectConfig.validateMappingPath("/data//user/10/example.app/files/*/"))
        assertNull(validateMappings(listOf(StoragePathMapping("Download/*/item?", "Pictures/*/copy?"))))
        assertEquals(MappingProblem.MissingCapture,
            validateMappings(listOf(StoragePathMapping("Download/*", "Pictures/*/?"))))
    }

    @Test
    fun mappingCyclesIncludeAliasesAndSelectedUsers() {
        assertEquals(MappingProblem.Cycle, validateMappings(listOf(
            StoragePathMapping("A", "/sdcard/B"), StoragePathMapping("B", "/storage/emulated/0/A")), 10))
        assertEquals(MappingProblem.Cycle, validateMappings(listOf(
            StoragePathMapping("{app_data_dir}/x", "/data/user/10/example.app/x")), 10, "example.app"))
        val chain = (0 until 10).map { StoragePathMapping("p$it", "p${it + 1}") }
        assertNull(validateMappings(chain))
        val tooDeep = chain + StoragePathMapping("p10", "last")
        assertEquals(MappingProblem.TooDeep, validateMappings(tooDeep))
        assertEquals(MappingProblem.TooDeep, validateMappings(tooDeep.reversed()))
    }

    @Test
    fun `excluded hidden segment is normalized`() {
        val result = RedirectConfig.validateAllowedPath("!Pictures/!.gs")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("!Pictures/.gs", (result as PathValidationResult.Valid).normalized)
    }

    @Test
    fun `allowed hidden segment keeps literal bang`() {
        val result = RedirectConfig.validateAllowedPath("Pictures/!.gs")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("Pictures/!.gs", (result as PathValidationResult.Valid).normalized)
    }

    @Test
    fun `excluded parent-like hidden segment keeps literal bang`() {
        val result = RedirectConfig.validateAllowedPath("!Pictures/!..")

        assertTrue(result is PathValidationResult.Valid)
        assertEquals("!Pictures/!..", (result as PathValidationResult.Valid).normalized)
    }
}
