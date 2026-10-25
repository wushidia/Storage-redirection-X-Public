package com.storage.redirect.x.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.ui.navigation.*
import com.storage.redirect.x.ui.page.*
import com.storage.redirect.x.ui.page.FakeAppSource
import com.storage.redirect.x.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiRevisionTest {
    @Before
    fun setup() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        AppPreferences.put("apps_sort_mode", "NAME")
        AppPreferences.put("apps_show_system", false)
    }

    @Test
    fun monitorFilterUsesTheAnchoredMenuAndAppliesImmediately() {
        val vm = FileMonitorViewModel()
        RenderScene {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                    FileMonitorPage(monitorViewModel = vm)
                }
            }
            .use { scene ->
                scene.frames()
                scene.tapTag("monitor_filter_menu")
                val create =
                    scene.activity.getString(
                        com.storage.redirect.x.R.string.file_monitor_action_create
                    )
                val rename =
                    scene.activity.getString(
                        com.storage.redirect.x.R.string.file_monitor_action_rename
                    )
                assertTrue(scene.texts(create).isNotEmpty())
                assertTrue(scene.texts(rename).isNotEmpty())
                assertTrue(scene.texts("取消").isEmpty())
                scene.capture("monitor-filter-menu")
                scene.tapText(create)
                assertEquals(2, vm.uiState.value.opTypeIndex)
                assertFalse(vm.uiState.value.isOpTypeMenuVisible)
            }
    }

    @Test
    fun homeCounterQueriesOnlyWhenTheAppEntersTheForeground() {
        var reads = 0
        val repo = com.storage.redirect.x.data.repository.ModuleRepository {
            reads++
            com.storage.redirect.x.data.service.CommandResult(0, "$reads", "")
        }
        var selectedPage by mutableIntStateOf(0)
        RenderScene {
            com.storage.redirect.x.RefreshStatsOnAppOpen(repo)
            top.yukonga.miuix.kmp.basic.Text("page=$selectedPage")
        }.use { scene ->
            scene.frames()
            assertEquals(1, reads)
            selectedPage = 2
            scene.frames()
            assertEquals(1, reads)
            scene.moveToBackground()
            scene.frames()
            assertEquals(1, reads)
            scene.moveToForeground()
            assertEquals(2, reads)
        }
    }

    @Test
    fun homeCounterReadsCurrentStatsAndMigratesItsOldCache() {
        val key = "home_redirect_count"
        AppPreferences.put(key, 42)
        com.storage.redirect.x.data.cache.HomeDataCache.restoreFromPrefs()
        assertEquals(42L, com.storage.redirect.x.data.cache.HomeDataCache.redirectCount)
        var value = "2147483648\n"
        var command = ""
        val repo = com.storage.redirect.x.data.repository.ModuleRepository {
            command = it
            com.storage.redirect.x.data.service.CommandResult(0, value, "")
        }
        kotlinx.coroutines.runBlocking {
            com.storage.redirect.x.data.cache.HomeDataCache.refreshRedirectCount(repo)
            assertEquals(2147483648L, com.storage.redirect.x.data.cache.HomeDataCache.redirectCount)
            assertEquals(2147483648L, AppPreferences.getLong(key))
            assertTrue(command.contains("/data/adb/modules/storage.redirect.x/stats"))
            assertFalse(command.contains("/data/local/tmp/storage.redirect.x_stats"))
            value = ""
            com.storage.redirect.x.data.cache.HomeDataCache.refreshRedirectCount(repo)
            assertEquals(2147483648L, com.storage.redirect.x.data.cache.HomeDataCache.redirectCount)
        }
        AppPreferences.put(key, 0L)
        com.storage.redirect.x.data.cache.HomeDataCache.restoreFromPrefs()
    }

    @Test
    fun restartActionAndItsDialogStayBounded() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { HomePage() }
            }
        }
            .use { scene ->
                scene.frames()
                val button = scene.tag("home_restart_media").boundsInRoot
                assertTrue(button.height < 240f)
                assertTrue(button.width > 800f)
                val status = scene.tag("home_module_status").boundsInRoot
                val apps = scene.tag("home_redirected_apps").boundsInRoot
                val count = scene.tag("home_redirect_count").boundsInRoot
                assertEquals(status.width, apps.width, 1f)
                assertEquals(status.top, apps.top, 1f)
                assertEquals(status.bottom, count.bottom, 1f)
                assertTrue(count.top > apps.bottom)
                assertTrue(button.top >= status.bottom)
                scene.capture("home-revised")
                scene.tapTag("home_restart_media")
                assertTrue(
                    "Window texts: ${scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }}",
                    scene.texts("确定").isNotEmpty(),
                )
                val confirm = scene.clickable(scene.texts("确定").first()).boundsInRoot
                val cancel = scene.clickable(scene.texts("取消").first()).boundsInRoot
                assertTrue(
                    "The confirmation action filled the screen: $confirm",
                    confirm.height < 240f,
                )
                assertEquals(confirm.height, cancel.height, 1f)
                scene.tapText("取消")
            }
    }

    @Test
    fun templatesTitleAndBackButtonShareOneToolbarRow() {
        val repository = com.storage.redirect.x.data.repository.TemplateRepository()
        repository.saveTemplate(com.storage.redirect.x.data.model.RedirectTemplate(id = "path-layout", name = "Path layout"))
        RenderScene { AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { TemplatesPage() } }
            .use { scene ->
                scene.frames()
                val title = scene.texts("模板").first().boundsInRoot
                val back =
                    scene
                        .nodes()
                        .first {
                            it.config
                                .getOrNull(SemanticsProperties.ContentDescription)
                                ?.contains("关闭") == true
                        }
                        .boundsInRoot
                assertEquals(title.center.y, back.center.y, 3f)
                scene.capture("templates-revised")
                scene.tapTag("template_edit_path-layout")
                scene.tapTag("template_add_allowed_path")
                val allowedInput = scene.tag("template_allowed_path_input").boundsInRoot
                val allowedBrowse = scene.tag("template_allowed_path_input_browse").boundsInRoot
                assertTrue(allowedBrowse.left >= allowedInput.right)
                assertEquals(allowedInput.center.y, allowedBrowse.center.y, 3f)
                scene.capture("template-path-browse-right")
                scene.tapTag("template_allowed_path_cancel")
                scene.tapTag("template_add_mapping")
                val requestInput = scene.tag("template_mapping_request_input").boundsInRoot
                val requestBrowse = scene.tag("template_mapping_request_input_browse").boundsInRoot
                val targetInput = scene.tag("template_mapping_final_input").boundsInRoot
                val targetBrowse = scene.tag("template_mapping_final_input_browse").boundsInRoot
                assertTrue(requestBrowse.left >= requestInput.right)
                assertTrue(targetBrowse.left >= targetInput.right)
                assertEquals(requestInput.left, targetInput.left, 1f)
                assertEquals(requestInput.right, targetInput.right, 1f)
                assertEquals(requestInput.center.y, requestBrowse.center.y, 3f)
                assertEquals(targetInput.center.y, targetBrowse.center.y, 3f)
                scene.capture("template-mapping-browse-right")
                scene.tapTag("template_mapping_cancel")
                scene.tapTag("rule_read_only_paths")
                scene.tapTag("policy_add")
                val policyInput = scene.tag("policy_path_input").boundsInRoot
                val policyBrowse = scene.tag("policy_browse").boundsInRoot
                assertTrue(policyBrowse.left >= policyInput.right)
                assertEquals(policyInput.center.y, policyBrowse.center.y, 3f)
            }
        repository.deleteTemplate("path-layout")
    }

    @Test
    fun themeModeUsesTheAnchoredBlurStyleMenu() {
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                ColorPalettePage(AppThemeSettings(colorMode = ColorMode.LIGHT), {}, {})
            }
        }
            .use { scene ->
                scene.frames()
                scene.tapText("主题模式")
                scene.capture("theme-mode-diagnostic")
                assertTrue(
                    "Texts: ${scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }}",
                    scene.texts("跟随系统").isNotEmpty(),
                )
                assertTrue(scene.texts("深色").isNotEmpty())
                assertTrue(
                    "An anchored choice menu must not add a dialog cancel button",
                    scene.texts("取消").isEmpty(),
                )
                scene.capture("theme-mode-menu")
            }
    }

    @Test
    fun configuredAppsUseMishkaCheckboxesAndSelectionConfirmsAtBottomRight() {
        val source = FakeAppSource()
        source.gate = kotlinx.coroutines.CompletableDeferred()
        val vm = AppsViewModel(source)
        vm.cacheConfig(0, 0, RedirectConfig().addApp("example.alpha"))
        RenderScene {
            AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) {
                AppsPage(appsViewModel = vm)
            }
        }
            .use { scene ->
                scene.frames()
                val loading = scene.tag("apps_loading_indicator").boundsInRoot
                assertEquals(scene.decor.width / 2f, loading.center.x, 3f)
                assertTrue(loading.center.y > scene.decor.height * 0.4f)
                scene.capture("apps-miuix-loading")
                source.gate?.complete(Unit)
                scene.frames(80)
                scene.capture("apps-diagnostic")
                assertTrue(
                    "Catalog: ${vm.uiState.value}; filtered: ${vm.filteredApps.value}; source reads: ${source.reads}",
                    vm.filteredApps.value.isNotEmpty(),
                )
                val card = scene.tag("app_item_example.alpha")
                fun nodes(node: SemanticsNode): List<SemanticsNode> =
                    listOf(node) + node.children.flatMap(::nodes)
                assertTrue(
                    nodes(card).any {
                        it.config.getOrNull(SemanticsProperties.ToggleableState) ==
                            ToggleableState.On
                    }
                )
                scene.capture("apps-configured-revised")
                card.config.getOrNull(SemanticsActions.OnLongClick)!!.action!!.invoke()
                scene.frames()
                val confirm = scene.tag("apps_selection_confirm").boundsInRoot
                assertTrue(confirm.left > scene.decor.width / 2f)
                assertTrue(confirm.bottom > scene.decor.height * 0.8f)
                assertTrue(confirm.height < 240f)
            }
    }

    @Test
    fun aboutUsesMishkaHeroAndItsSolidFallback() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { AboutPage() }
            }
        }
            .use { scene ->
                scene.frames()
                assertTrue(scene.texts("存储重定向 X").isNotEmpty())
                scene.capture("about-solid")
            }
    }

    @Test
    fun aboutUsesMishkaAnimatedGlass() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides true,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { AboutPage() }
            }
        }
            .use { scene ->
                scene.frames()
                assertTrue(scene.texts("存储重定向 X").isNotEmpty())
                scene.capture("about-glass")
                val scroller =
                    scene.nodes().first {
                        it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                    }
                scroller.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f, 1500f)
                scene.frames(80)
                scene.capture("about-scrolled")
            }
    }

    @Test
    fun settingsHasOneAboutEntryAndUsesAnchoredLanguageMenu() {
        val navigator = AppNavigator()
        RenderScene {
            CompositionLocalProvider(
                LocalNavigator provides navigator,
                LocalEnableBlur provides false,
            ) {
                AppTheme(AppThemeSettings(colorMode = ColorMode.LIGHT)) { SettingsPage() }
            }
        }
            .use { scene ->
                scene.frames()
                scene.tapText("语言")
                assertTrue(scene.texts("取消").isEmpty())
                scene.capture("language-menu")
                // Dismiss by tapping the currently selected option; no activity recreation occurs
                // here.
                val menuOption = scene.texts("跟随系统").last()
                scene
                    .clickable(menuOption)
                    .config
                    .getOrNull(SemanticsActions.OnClick)!!
                    .action!!
                    .invoke()
                scene.frames()
                val scroller =
                    scene.nodes().first {
                        it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null
                    }
                scroller.config.getOrNull(SemanticsActions.ScrollBy)!!.action!!.invoke(0f, 2000f)
                scene.frames(80)
                assertEquals(
                    1,
                    scene.nodes().count {
                        it.config.getOrNull(SemanticsProperties.TestTag) == "settings_about_entry"
                    },
                )
                scene.tapTag("settings_about_entry")
                assertEquals("about", (navigator.backStack.last() as ScreenRoute).page)
            }
    }
}
