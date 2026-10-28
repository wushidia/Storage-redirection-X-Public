package com.storage.redirect.x.ui.page

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.AppInfo
import com.storage.redirect.x.data.model.RedirectConfig
import com.storage.redirect.x.data.provider.AppCatalogSource
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

internal class FakeAppSource : AppCatalogSource {
    override suspend fun getUsers() = persistentListOf(com.storage.redirect.x.data.service.SystemUser(0, "Owner"),
        com.storage.redirect.x.data.service.SystemUser(10, "Work"))
    val userReads = mutableListOf<Int>()
    var reads = 0
    var iconReads = 0
    var timeReads = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun getInstalledApps(userId: Int, forceRefresh: Boolean): ImmutableList<AppInfo> {
        reads++
        userReads += userId
        gate?.await()
        return persistentListOf(
            AppInfo("example.alpha", "Alpha", false),
            AppInfo("example.beta", "Beta", false),
            AppInfo("system.app", "System", true),
        ).map { it.copy(userId = userId, uid = userId * 100000 + 10001) }.toPersistentList()
    }

    override suspend fun getIcon(packageName: String, userId: Int): Drawable {
        iconReads++
        return ColorDrawable(android.graphics.Color.GRAY)
    }

    override suspend fun getInstallTimes(apps: ImmutableList<AppInfo>): ImmutableList<AppInfo> {
        timeReads++
        return apps
            .mapIndexed { index, app -> app.copy(firstInstallTime = index.toLong()) }
            .toPersistentList()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AppsViewModelTest {
    private lateinit var store: ViewModelStore

    @Before
    fun setup() {
        AppPreferences.init(RuntimeEnvironment.getApplication())
        AppPreferences.put("apps_sort_mode", "NAME")
        AppPreferences.put("apps_show_system", false)
        AppPreferences.put("apps_redirected_first", true)
        store = ViewModelStore()
    }

    @After
    fun close() {
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun create(source: FakeAppSource): AppsViewModel =
        AppsViewModel(source).also {
            it.cacheConfig(0, 0, RedirectConfig())
            store.put("apps", it)
        }

    private fun drain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun catalogStaysHiddenUntilRedirectedAppsAreKnown() {
        val source = FakeAppSource()
        val vm = AppsViewModel(source).also { store.put("apps", it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val visible = mutableListOf<List<String>>()
        val subscription = scope.launch {
            vm.filteredApps.collect { apps -> visible += apps.map { it.packageName } }
        }
        try {
            drain()
            assertEquals(1, source.reads)
            assertEquals(3, vm.uiState.value.apps.size)
            assertTrue(vm.uiState.value.isConfigLoading)
            assertTrue(vm.filteredApps.value.isEmpty())
            vm.cacheConfig(0, 0, RedirectConfig(redirectApps = listOf("example.beta")))
            drain()
            assertFalse(vm.uiState.value.isConfigLoading)
            assertEquals(listOf("example.beta", "example.alpha"), visible.first { it.isNotEmpty() })
            assertEquals(0, source.iconReads)
            vm.selectUser(0)
            drain()
            assertFalse(vm.uiState.value.isConfigLoading)
        } finally {
            subscription.cancel()
            scope.cancel()
        }
    }

    @Test
    fun userSwitchLoadsOnlyThatUsersCatalogAndIgnoresOldConfigResults() {
        val source = FakeAppSource()
        val vm = create(source)
        drain()
        vm.ensureUsersLoaded()
        drain()
        vm.selectUser(1)
        vm.cacheConfig(10, 1, RedirectConfig(userId = 10, redirectApps = listOf("example.beta")))
        drain()
        assertEquals(listOf(0, 10), source.userReads)
        assertTrue(vm.uiState.value.apps.all { it.userId == 10 })
        assertEquals(10, vm.uiState.value.catalogUserId)
        vm.cacheConfig(0, 2, RedirectConfig())
        assertEquals(10, vm.uiState.value.configUserId)
        assertEquals(setOf("example.beta"), vm.uiState.value.redirectedPackages)
        vm.selectUser(0)
        drain()
        assertEquals(listOf(0, 10), source.userReads)
        assertTrue(vm.uiState.value.apps.all { it.userId == 0 })
    }

    class PackageSlice(private val infos: List<android.content.pm.PackageInfo>) {
        fun getList() = infos
    }
    class PackageApi32 {
        var lastUser = -1
        fun getInstalledPackages(flags: Int, userId: Int): PackageSlice {
            assertEquals(0, flags)
            lastUser = userId
            return slice(userId)
        }
    }
    class PackageApi33 {
        var lastUser = -1
        fun getInstalledPackages(flags: Long, userId: Int): PackageSlice {
            assertEquals(0L, flags)
            lastUser = userId
            return slice(userId)
        }
    }
    class UserInfo(@JvmField val id: Int, @JvmField val name: String)
    class UserApi {
        fun getUsers(partial: Boolean, dying: Boolean, preCreated: Boolean): List<UserInfo> {
            assertTrue(partial && dying && preCreated)
            return listOf(UserInfo(0, "Owner"), UserInfo(10, "Work"))
        }
    }
    class SingleFlagUserApi {
        fun getUsers(dying: Boolean): List<UserInfo> {
            assertTrue(dying)
            return listOf(UserInfo(0, "Owner"), UserInfo(10, "Work"))
        }
    }
    class FailingUserApi {
        fun getUsers(partial: Boolean, dying: Boolean, preCreated: Boolean): List<UserInfo> {
            assertTrue(partial && dying && preCreated)
            throw SecurityException("user service denied")
        }
    }
    companion object {
        fun slice(userId: Int): PackageSlice = PackageSlice(listOf(0, userId).distinct().map {
            android.content.pm.PackageInfo().apply {
                packageName = "example.app"
                applicationInfo = android.content.pm.ApplicationInfo().apply { uid = it * 100000 + 10001 }
            }
        })
    }

    @Test
    fun packageApiUsesVersionedFlagsAndExplicitUserIds() {
        val users = UserApi()
        val old = PackageApi32()
        val modern = PackageApi33()
        for ((service, sdk) in listOf(old to 32, modern to 35)) {
            val api = org.srx.packages.RootPackageApi(service, service.javaClass, users, users.javaClass, sdk)
            assertEquals(1010001, api.installedPackages(10).single().applicationInfo?.uid)
            assertEquals(10, api.users().getJSONObject(1).getInt("id"))
        }
        assertEquals(10, old.lastUser)
        assertEquals(10, modern.lastUser)
    }

    @Test
    fun singleFlagUserApiKeepsAllRealUsers() {
        val packages = PackageApi33()
        val users = SingleFlagUserApi()
        val api = org.srx.packages.RootPackageApi(packages, packages.javaClass, users, users.javaClass, 37)
        assertEquals(listOf(0, 10), api.users().let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).getInt("id") }
        })
        assertEquals(1010001, api.installedPackages(10).single().applicationInfo?.uid)
    }

    @Test
    fun userServiceFailureIsNotReplacedWithAnotherSignature() {
        val packages = PackageApi33()
        val users = FailingUserApi()
        val api = org.srx.packages.RootPackageApi(packages, packages.javaClass, users, users.javaClass, 37)
        try {
            api.users()
            fail("user service error was swallowed")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            assertTrue(error.cause is SecurityException)
        }
    }

    @Test
    fun profileWritesPreserveOtherUsersAndExistingInstallRules() {
        val directory = java.nio.file.Files.createTempDirectory("srx-profiles").toFile()
        try {
            val store = org.srx.packages.ConfigProfileStore(directory)
            val original = org.json.JSONObject().put("enabled", false)
                .put("read_only_paths", org.json.JSONArray().put("Pictures"))
            store.writeProfile("example.app", 0, original, false)
            store.writeProfile("example.app", 10, org.json.JSONObject().put("enabled", true), false)
            val result = store.writeProfile("example.app", 0, org.json.JSONObject().put("enabled", true), true)
            val users = result.getJSONObject("users")
            assertTrue(users.getJSONObject("10").getBoolean("enabled"))
            assertEquals("Pictures", users.getJSONObject("0").getJSONArray("read_only_paths").getString(0))
            val disabled = store.writeProfile("example.app", 10, org.json.JSONObject().put("enabled", false), true, true)
            assertTrue(disabled.getJSONObject("users").getJSONObject("0").getBoolean("enabled"))
            assertFalse(disabled.getJSONObject("users").getJSONObject("10").getBoolean("enabled"))
            val archive = java.io.File(directory, "archive")
            val remaining = store.removeProfile("example.app", 10, archive).getJSONObject("users")
            assertTrue(remaining.has("0"))
            assertFalse(remaining.has("10"))
            assertTrue(java.io.File(directory, "apps/example.app.json").isFile)
            store.removeProfile("example.app", 0, archive)
            assertFalse(java.io.File(directory, "apps/example.app.json").exists())
            assertTrue(java.io.File(archive, "example.app.removed.json").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun enteringAgainReusesTheLoadedListAndSearch() {
        val source = FakeAppSource()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        vm.setQuery("Beta")
        vm.ensureAppsLoaded()
        drain()
        assertEquals(1, source.reads)
        assertEquals("Beta", vm.uiState.value.query)
        assertFalse(vm.uiState.value.isLoading)
        vm.refreshApps()
        drain()
        assertEquals(2, source.reads)
        assertEquals("Beta", vm.uiState.value.query)
    }

    @Test
    fun onlyExplicitInstallTimeSortQueriesInstallTimes() {
        val source = FakeAppSource()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        assertEquals(0, source.timeReads)
        vm.setSortByName(false)
        drain()
        assertEquals(1, source.timeReads)
        vm.setSortByName(true)
        vm.setSortByName(false)
        drain()
        assertEquals(1, source.timeReads)
        assertEquals(1, source.reads)
    }

    @Test
    fun concurrentEntryAndRefreshShareTheInFlightLoad() {
        val source = FakeAppSource()
        source.gate = CompletableDeferred()
        val vm = create(source)
        vm.ensureAppsLoaded()
        drain()
        vm.ensureAppsLoaded()
        vm.refreshApps()
        drain()
        assertEquals(1, source.reads)
        source.gate!!.complete(Unit)
        drain()
        assertFalse(vm.uiState.value.isLoading)
        vm.refreshApps()
        drain()
        assertEquals(2, source.reads)
    }

    @Test
    fun filtersAndIconUpdatesDoNotReenumerateOrResortTheCatalog() {
        val source = FakeAppSource()
        val vm = create(source)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val subscription = scope.launch { vm.filteredApps.collect {} }
        try {
            drain()
            assertEquals(2, vm.filteredApps.value.size)
            vm.setQuery("Beta")
            drain()
            assertEquals("example.beta", vm.filteredApps.value.single().packageName)
            val filtered = vm.filteredApps.value
            vm.requestIcon("example.beta")
            repeat(100) {
                drain()
                if (vm.uiState.value.icons.isEmpty()) Thread.sleep(5)
            }
            assertEquals(1, source.iconReads)
            vm.requestIcon("example.beta")
            drain()
            assertEquals(1, source.iconReads)
            assertSame(filtered, vm.filteredApps.value)
            vm.setQuery("")
            vm.setShowSystemApps(true)
            drain()
            assertEquals(3, vm.filteredApps.value.size)
            assertEquals(1, source.reads)
        } finally {
            subscription.cancel()
            scope.cancel()
        }
    }
}
