package com.storage.redirect.x.data.model

import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Test

class RedirectTemplateTest {
    @Test
    fun advancedTemplateRulesAreBackedUpAndAppliedTogether() {
        val template = RedirectTemplate(id = "advanced", name = "Mapped",
            isMappingModeOnly = true, sandboxedPaths = persistentListOf("Cache", "!Cache/open"),
            readOnlyPaths = persistentListOf("Pictures", "!Pictures/import"),
            pathMappings = persistentListOf(StoragePathMapping("Download/*", "Pictures/*")),
            createdAt = 1, updatedAt = 2).normalized()
        val restored = RedirectTemplate.listFromJson("{\"templates\":[${template.toJson()}]}").single()
        assertEquals(template, restored)
        val repo = com.storage.redirect.x.data.repository.TemplateRepository()
        val applied = repo.applyTemplate(RedirectConfig(userId = 10), "example.app", listOf("example.shared"),
            restored, com.storage.redirect.x.data.repository.TemplateApplyMode.Replace)
        for (pkg in listOf("example.app", "example.shared")) {
            val profile = requireNotNull(applied.config.getAppConfig(pkg))
            assertEquals(template.isMappingModeOnly, profile.isMappingModeOnly)
            assertEquals(template.readOnlyPaths, profile.readOnlyPaths)
            assertEquals(template.sandboxedPaths, profile.sandboxedPaths)
            assertEquals(template.pathMappings, profile.pathMappings)
        }
    }

    @Test
    fun creatingATemplateRetainsAllAdvancedRuleFields() {
        val source = AppRedirectConfig("example.app", isMappingModeOnly = true,
            sandboxedPaths = listOf("Cache"), readOnlyPaths = listOf("Pictures"))
        val repo = com.storage.redirect.x.data.repository.TemplateRepository()
        val template = repo.createTemplateFromApp("Example", source)
        assertEquals(source.isMappingModeOnly, template.isMappingModeOnly)
        assertEquals(source.readOnlyPaths, template.readOnlyPaths)
        assertEquals(source.sandboxedPaths, template.sandboxedPaths)
    }
    @Test
    fun immutableTemplateKeepsTheExistingJsonFormat() {
        val template =
            RedirectTemplate(
                id = "template",
                name = "Photos",
                allowedRealPaths = persistentListOf("DCIM", "Pictures"),
                pathMappings = persistentListOf(StoragePathMapping("Download", "Pictures")),
                createdAt = 1L,
                updatedAt = 2L,
            )
        val json = template.toJson()
        assertEquals("DCIM", json.getAsJsonArray("allowed_real_paths")[0].asString)
        assertEquals("Pictures", json.getAsJsonObject("path_mappings")["Download"].asString)
        assertEquals(template, RedirectTemplate.fromJson(json))
    }

    @Test
    fun normalizingATemplatePreservesTheSourceSnapshot() {
        val original =
            RedirectTemplate(
                id = "template",
                name = "  Photos  ",
                allowedRealPaths = persistentListOf("Pictures", "DCIM", "Pictures"),
            )
        val normalized = original.normalized()
        assertEquals(listOf("DCIM", "Pictures"), normalized.allowedRealPaths)
        assertEquals("Photos", normalized.name)
        assertEquals(3, original.allowedRealPaths.size)
        assertEquals("  Photos  ", original.name)
    }
}
