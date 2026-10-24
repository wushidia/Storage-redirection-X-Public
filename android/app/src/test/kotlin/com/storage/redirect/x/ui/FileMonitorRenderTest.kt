package com.storage.redirect.x.ui

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.ViewModelStore
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.FileMonitorEntry
import com.storage.redirect.x.data.repository.FileMonitorRepository
import com.storage.redirect.x.data.repository.MonitorPage
import com.storage.redirect.x.ui.page.FileMonitorPage
import com.storage.redirect.x.ui.page.FileMonitorViewModel
import com.storage.redirect.x.ui.theme.*
import kotlinx.collections.immutable.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class FileMonitorRenderTest {
    @Test
    @Suppress("UNCHECKED_CAST")
    fun selectAllKeepsSelectionsOutsideTheVisibleFolders() {
        val state = com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState(0, null, true, true)
        val field = state.javaClass.getDeclaredField("directories\$delegate").apply { isAccessible = true }
        (field.get(state) as MutableState<List<String>>).value = listOf(".hidden", "Download")
        state.togglePath("Elsewhere/saved")
        state.togglePath("Download")
        state.query = ".hidden"
        state.toggleVisibleSelection()
        assertEquals(setOf("Elsewhere/saved", "Download", ".hidden"), state.selectedPaths)
        state.toggleVisibleSelection()
        assertEquals(setOf("Elsewhere/saved", "Download"), state.selectedPaths)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun directoryPickerKeepsSelectionAndConfirmsAtTopRight() {
        val state = com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState(0, null, true, true)
        fun <T> setField(name: String, value: T) {
            val field = state.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
            (field.get(state) as MutableState<T>).value = value
        }
        setField("directories", listOf(".hidden", "Download", "Pictures", "Long folder name for checking two line text alignment"))
        setField("isLoading", false)
        state.togglePath("Download")
        var confirmed = emptyList<String>()
        var creationAttempts = 0
        val repository = com.storage.redirect.x.data.repository.PathBrowserRepository {
            creationAttempts++
            com.storage.redirect.x.data.service.CommandResult(0, if (creationAttempts == 1) "exists" else "created", "")
        }
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                com.storage.redirect.x.ui.page.pathbrowser.BrowserScaffold(
                    title = "选择目录", state = state, onBack = {},
                    onConfirm = { confirmed = state.confirmedPaths() }, modifier = androidx.compose.ui.Modifier,
                ) { padding ->
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier
                        .then(androidx.compose.ui.Modifier.padding(padding))) {
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserBreadcrumbs(state)
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserSearch(state.query) { state.query = it }
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserDirectoryList(state, androidx.compose.ui.Modifier.weight(1f))
                    }
                }
                com.storage.redirect.x.ui.page.pathbrowser.PathBrowserDialogs(state, repository)
            }
        }.use { scene ->
            scene.frames()
            verifyFolderCreation(scene, state)
            assertEquals(2, creationAttempts)
            scene.tapTag("browser_select_Pictures")
            scene.frames()
            assertEquals(setOf("Download", "Pictures"), state.selectedPaths)
            val confirm = scene.tag("directory_confirm").boundsInRoot
            assertTrue(confirm.left > 700f)
            assertTrue(confirm.top < 250f)
            val actions = listOf("browser_sort", "browser_new_folder", "browser_select_all", "directory_confirm")
                .map { scene.tag(it).boundsInRoot }
            actions.zipWithNext().forEach { (left, right) ->
                assertTrue(left.right <= right.left)
                assertTrue(kotlin.math.abs(left.center.y - right.center.y) < 2f)
            }
            val mode = scene.tag("browser_rule_mode").boundsInRoot
            assertTrue(mode.top >= confirm.bottom)
            assertTrue(mode.bottom < scene.tag("browser_directory_Download").boundsInRoot.top)
            assertTrue(scene.nodes().none { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) == "browser_full_path" })
            assertTrue(scene.texts("文件夹 · 4").isEmpty())
            assertTrue(scene.texts("名称升序").isEmpty())
            assertTrue(scene.texts(".hidden").isNotEmpty())
            scene.tapTag("browser_select_all")
            assertEquals(4, state.selectedPaths.size)
            scene.tapTag("browser_select_all")
            assertTrue(state.selectedPaths.isEmpty())
            scene.tapTag("browser_select_Download")
            scene.tapTag("browser_select_Pictures")
            scene.capture("directory-picker-actions")
            scene.tapTag("browser_sort")
            assertTrue(scene.texts("名称降序").isNotEmpty())
            scene.tapText("名称降序")
            assertTrue(state.isDescending)
            scene.tapText("排除目录")
            scene.frames()
            assertTrue(state.isExcludeMode)
            scene.tapTag("directory_confirm")
            assertEquals(setOf("!Download", "!Pictures"), confirmed.toSet())
        }
    }


    @Test
    @Suppress("UNCHECKED_CAST")
    fun directoryPickerRendersEmptyStateWithDarkMaterials() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        val state = com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState(0, null, false, false)
        val loading = state.javaClass.getDeclaredField("isLoading\$delegate").apply { isAccessible = true }
        (loading.get(state) as MutableState<Boolean>).value = false
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.DARK)) {
                com.storage.redirect.x.ui.page.pathbrowser.BrowserScaffold(
                    title = "选择目录", state = state, onBack = {},
                    onConfirm = {}, modifier = androidx.compose.ui.Modifier,
                ) { padding ->
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(padding)) {
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserBreadcrumbs(state)
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserSearch(state.query) { state.query = it }
                        com.storage.redirect.x.ui.page.pathbrowser.BrowserDirectoryList(state, androidx.compose.ui.Modifier.weight(1f))
                    }
                }
            }
        }.use { scene ->
            scene.frames()
            assertTrue(scene.texts("空目录").isNotEmpty())
            scene.capture("directory-picker-actions-empty-dark")
        }
    }

    private fun verifyFolderCreation(scene: RenderScene, state: com.storage.redirect.x.ui.page.pathbrowser.PathBrowserState) {
        scene.tapTag("browser_new_folder")
        scene.frames(80)
        val edit = scene.tag("browser_folder_name")
        requireNotNull(edit.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.SetText)?.action)
            .invoke(androidx.compose.ui.text.AnnotatedString("New folder"))
        scene.frames()
        scene.tapTag("browser_create_confirm")
        assertTrue(scene.texts("已存在同名文件或文件夹").isNotEmpty())
        assertEquals(0, state.reloadKey)
        scene.tapTag("browser_create_confirm")
        assertNull(state.dialog)
        assertEquals(1, state.reloadKey)
        assertEquals("", state.query)
    }

    @Test
    fun emptySearchKeepsPagingAndMonitoringControl() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        var requested: Boolean? = null
        val cursors = mutableListOf<Long>()
        val repository = object : FileMonitorRepository() {
            override suspend fun loadPage(before: Long, after: Long): MonitorPage {
                cursors += before
                return MonitorPage(persistentListOf(), hasMore = cursors.size == 1,
                    daemonUpdatedMs = System.currentTimeMillis())
            }
            override suspend fun setMonitoringEnabled(enabled: Boolean): Boolean {
                requested = enabled
                return true
            }
        }
        val store = ViewModelStore()
        val vm = FileMonitorViewModel(repository).also { store.put("monitor", it) }
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { FileMonitorPage(monitorViewModel = vm) }
        }.use { scene ->
            repeat(30) { scene.frames(2); if (vm.uiState.value.isLoading) Thread.sleep(5) }
            vm.updateSearchQuery("missing.txt")
            scene.frames()
            assertTrue(scene.texts("暂无监视记录").isNotEmpty())
            scene.tag("monitor_load_more")
            scene.tapTag("monitor_toggle")
            repeat(30) { scene.frames(2); if (vm.uiState.value.isChangingMonitoring) Thread.sleep(5) }
            assertEquals(false, requested)
            assertFalse(vm.uiState.value.isMonitoringEnabled)
            scene.capture("monitor-empty-search")
            scene.tapTag("monitor_load_more")
            repeat(30) { scene.frames(2); if (vm.uiState.value.isLoadingMore) Thread.sleep(5) }
            assertEquals(2, cursors.size)
            assertFalse(vm.uiState.value.hasMore)
        }
        store.clear()
    }

    @Test
    fun repeatedWritesAndOlderPagesShareOneCountedRow() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        val cursors = mutableListOf<Long>()
        fun event(time: Long, seq: Long) =
            FileMonitorEntry(
                "2026-10-24 12:00:00",
                "example.writer",
                "example.writer",
                "WRITE",
                "/storage/emulated/0/Download/test.txt",
                resultCode = 4096,
                errorNo = 0,
                timeMillis = time,
                bytes = 4096,
                sequence = seq,
            )
        val repository =
            object : FileMonitorRepository() {
                override suspend fun loadPage(before: Long, after: Long): MonitorPage {
                    cursors += before
                    return if (before == 0L)
                        MonitorPage(
                            persistentListOf(event(10000, 3), event(9900, 2)),
                            hasMore = true,
                            daemonUpdatedMs = System.currentTimeMillis(),
                        )
                    else
                        MonitorPage(
                            persistentListOf(event(9800, 1)),
                            daemonUpdatedMs = System.currentTimeMillis(),
                        )
                }
            }
        val store = ViewModelStore()
        val vm = FileMonitorViewModel(repository).also { store.put("monitor", it) }
        RenderScene {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                    FileMonitorPage(monitorViewModel = vm)
                }
            }
            .use { scene ->
                repeat(30) {
                    scene.frames(2)
                    if (vm.uiState.value.isLoading) Thread.sleep(5)
                }
                assertEquals(2L, vm.uiState.value.entries.single().count)
                assertTrue(scene.texts("通过文件系统写入 2 次").isNotEmpty())
                scene.tapText("加载更早记录")
                repeat(30) {
                    scene.frames(2)
                    if (vm.uiState.value.isLoadingMore) Thread.sleep(5)
                }
                assertEquals(listOf(0L, 2L), cursors)
                assertEquals(3L, vm.uiState.value.entries.single().count)
                assertTrue(scene.texts("通过文件系统写入 3 次").isNotEmpty())
                assertFalse(vm.uiState.value.hasMore)
                scene.capture("monitor-grouped-writes")
            }
        store.clear()
    }
}
