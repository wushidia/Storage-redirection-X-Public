package com.storage.redirect.x.ui.page

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storage.redirect.x.data.cache.AppPreferences
import com.storage.redirect.x.data.model.*
import com.storage.redirect.x.data.provider.AppCatalogSource
import com.storage.redirect.x.data.repository.*
import com.storage.redirect.x.data.service.SystemUser
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Immutable
data class AppsCatalogState(
    val apps: ImmutableList<AppInfo> = persistentListOf(),
    val icons: ImmutableMap<String, Drawable> = persistentMapOf(),
    val users: ImmutableList<SystemUser> = persistentListOf(SystemUser(android.os.Process.myUid() / 100000, "")),
    val userIndex: Int = 0,
    val query: String = "",
    val showSystemApps: Boolean = AppPreferences.getBoolean("apps_show_system", false),
    val redirectedFirst: Boolean = AppPreferences.getBoolean("apps_redirected_first", true),
    val sortByName: Boolean = AppPreferences.getString("apps_sort_mode", "NAME") != "INSTALL_TIME",
    val sharedUidPackages: PersistentSet<String> = persistentSetOf(),
    val iconGeneration: Int = 0,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isConfigLoading: Boolean = true,
    val catalogUserId: Int? = null,
    val configUserId: Int? = null,
    val configVersion: Int? = null,
    val redirectedPackages: PersistentSet<String> = persistentSetOf(),
    val error: String? = null,
)

class AppsViewModel(private val source: AppCatalogSource) : ViewModel() {
    private val state = MutableStateFlow(AppsCatalogState())
    val uiState: StateFlow<AppsCatalogState> = state.asStateFlow()
    val configRepo = ConfigRepository()
    val sharedUidRepo = SharedUidRepository()
    val templateRepo = TemplateRepository()
    private val configs = mutableMapOf<Int, Pair<Int, RedirectConfig>>()
    private var loadJob: Job? = null
    private var usersJob: Job? = null
    private var timeJob: Job? = null
    private var generation = 0
    private var timesLoaded = false
    private val catalogs = mutableMapOf<Int, Pair<ImmutableList<AppInfo>, ImmutableMap<String, Drawable>>>()
    private val selectedUserId: Int get() = state.value.users[state.value.userIndex].id
    private val iconJobs = mutableMapOf<String, Job>()
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(2)

    private data class ListInput(
        val apps: ImmutableList<AppInfo>,
        val query: String,
        val showSystemApps: Boolean,
        val redirectedFirst: Boolean,
        val sortByName: Boolean,
        val isConfigLoading: Boolean,
        val redirectedPackages: PersistentSet<String>,
    )

    val filteredApps: StateFlow<ImmutableList<AppInfo>> =
        state
            .map {
                ListInput(it.apps, it.query, it.showSystemApps, it.redirectedFirst,
                    it.sortByName, it.isConfigLoading || it.catalogUserId != it.configUserId, it.redirectedPackages)
            }
            .distinctUntilChanged()
            .map { input ->
                if (input.isConfigLoading) return@map persistentListOf<AppInfo>()
                val filtered =
                    input.apps.filter {
                        (input.showSystemApps || !it.isSystemApp) &&
                            (input.query.isBlank() ||
                                it.appName.contains(input.query, true) ||
                                it.packageName.contains(input.query, true))
                    }
                val ordering =
                    if (input.sortByName) compareBy<AppInfo> { it.appName.lowercase() }
                    else
                        compareByDescending<AppInfo> { it.firstInstallTime }
                            .thenBy { it.appName.lowercase() }
                val comparator =
                    if (input.redirectedFirst)
                        compareByDescending<AppInfo> { it.packageName in input.redirectedPackages }.then(ordering)
                    else ordering
                filtered.sortedWith(comparator).toPersistentList()
            }
            .onStart { ensureAppsLoaded() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, persistentListOf())

    fun ensureAppsLoaded() {
        if (loadJob == null) loadApps(false)
    }

    fun refreshApps() {
        if (loadJob?.isActive != true) loadApps(true)
    }

    private fun loadApps(refresh: Boolean) {
        loadJob?.cancel()
        val userId = selectedUserId
        val requestGeneration = ++generation
        loadJob = viewModelScope.launch {
            state.update { it.copy(isRefreshing = refresh, error = null) }
            timeJob?.cancel()
            timesLoaded = false
            iconJobs.values.toList().forEach { it.cancel() }
            iconJobs.clear()
            try {
                val cached = if (!refresh) catalogs[userId] else null
                val apps = cached?.first ?: source.getInstalledApps(userId, refresh)
                if (requestGeneration != generation) return@launch
                sharedUidRepo.update(userId, apps)
                if (requestGeneration == generation)
                    state.update {
                        it.copy(
                            apps = apps,
                            icons = cached?.second ?: persistentMapOf(),
                            catalogUserId = userId,
                            sharedUidPackages = sharedUidRepo.allSharedUidPackages(userId).toPersistentSet(),
                            iconGeneration = requestGeneration,
                        )
                    }
                catalogs[userId] = apps to state.value.icons
                if (!state.value.sortByName) ensureInstallTimes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update {
                    it.copy(
                        error = e.message ?: e.javaClass.simpleName,
                        iconGeneration = requestGeneration,
                    )
                }
            } finally {
                if (requestGeneration == generation)
                    state.update { it.copy(isLoading = false, isRefreshing = false) }
            }
        }
    }

    fun requestIcon(packageName: String) {
        if (packageName in state.value.icons || packageName in iconJobs) return
        val requestGeneration = generation
        val userId = selectedUserId
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val currentJob = coroutineContext.job
                try {
                    val icon = withContext(iconDispatcher) { source.getIcon(packageName, userId) }
                    if (icon != null && requestGeneration == generation)
                        state.update {
                            val icons = it.icons.toPersistentMap().putting(packageName, icon)
                            catalogs[userId] = it.apps to icons
                            it.copy(icons = icons)
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    /* Keep a placeholder if an application disappears during lookup. */
                } finally {
                    if (iconJobs[packageName] == currentJob) iconJobs.remove(packageName)
                }
            }
        iconJobs[packageName] = job
        job.start()
    }

    fun ensureUsersLoaded(force: Boolean = false) {
        if (usersJob?.isActive == true || (!force && usersJob != null)) return
        usersJob = viewModelScope.launch {
            try {
                val selected = selectedUserId
                val users = source.getUsers()
                check(users.isNotEmpty()) { "Package API returned no Android users" }
                val index = users.indexOfFirst { it.id == selected }.coerceAtLeast(0)
                state.update { it.copy(users = users, userIndex = index) }
                if (selected != selectedUserId) loadApps(false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun cachedConfig(userId: Int): RedirectConfig? = configs[userId]?.second

    suspend fun configFor(userId: Int, version: Int, force: Boolean = false): RedirectConfig {
        if (userId == selectedUserId) state.update { it.copy(isConfigLoading = true) }
        val cached = configs[userId]
        val config =
            if (!force && cached?.first == version) cached.second else configRepo.load(userId)
        cacheConfig(userId, version, config)
        return config
    }

    fun cacheConfig(userId: Int, version: Int, config: RedirectConfig) {
        configs[userId] = version to config
        if (userId != selectedUserId) return
        state.update {
            it.copy(isConfigLoading = false, configUserId = userId, configVersion = version,
                redirectedPackages = config.redirectApps.toPersistentSet())
        }
    }

    fun setQuery(query: String) {
        state.update { it.copy(query = query) }
    }

    fun selectUser(index: Int) {
        val target = index.coerceIn(state.value.users.indices)
        if (target == state.value.userIndex) return
        state.update { it.copy(userIndex = target, isConfigLoading = true, isLoading = true,
            apps = persistentListOf(), icons = persistentMapOf(), sharedUidPackages = persistentSetOf()) }
        loadApps(false)
    }

    fun setShowSystemApps(value: Boolean) {
        state.update { it.copy(showSystemApps = value) }
        AppPreferences.put("apps_show_system", value)
    }

    fun setRedirectedFirst(value: Boolean) {
        state.update { it.copy(redirectedFirst = value) }
        AppPreferences.put("apps_redirected_first", value)
    }

    fun setSortByName(value: Boolean) {
        state.update { it.copy(sortByName = value) }
        AppPreferences.put("apps_sort_mode", if (value) "NAME" else "INSTALL_TIME")
        if (!value && !state.value.isLoading && !state.value.isRefreshing) ensureInstallTimes()
    }

    private fun ensureInstallTimes() {
        if (timesLoaded || timeJob?.isActive == true) return
        val requestGeneration = generation
        timeJob = viewModelScope.launch {
            try {
                val apps = source.getInstallTimes(state.value.apps)
                if (requestGeneration == generation) {
                    timesLoaded = true
                    state.update { it.copy(apps = apps) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
