package com.storage.redirect.x.ui.navigation

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

// 主 tab 导航状态：点击/深链切页走匀速感更自然的 tween，滑动跟随手势
@Stable
class RootNavigationState(
    val pagerState: PagerState,
    val tabCount: Int,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage.coerceIn(0 until tabCount))
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navigationJob: Job? = null

    fun animateTo(index: Int) {
        if (index !in 0 until tabCount) return
        if (index == selectedPage) return

        navigationJob?.cancel()
        selectedPage = index
        isNavigating = true

        val pageDistance = abs(index - pagerState.currentPage).coerceAtLeast(2)
        val durationMillis = 100 * pageDistance + 100
        val layoutInfo = pagerState.layoutInfo
        val pageSize = layoutInfo.pageSize + layoutInfo.pageSpacing
        val distanceInPages = index - pagerState.currentPage - pagerState.currentPageOffsetFraction

        navigationJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.animateScrollBy(
                    value = distanceInPages * pageSize,
                    animationSpec = tween(
                        durationMillis = durationMillis,
                        easing = EaseInOut,
                    ),
                )
            } finally {
                if (navigationJob === currentJob) {
                    isNavigating = false
                    selectedPage = pagerState.currentPage
                }
            }
        }
    }

    fun syncPage() {
        if (isNavigating || selectedPage == pagerState.currentPage) return
        selectedPage = pagerState.currentPage
    }
}

@Composable
fun rememberRootNavigationState(tabCount: Int): RootNavigationState {
    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { tabCount })
    val coroutineScope = rememberCoroutineScope()
    return remember(pagerState, tabCount, coroutineScope) {
        RootNavigationState(
            pagerState = pagerState,
            tabCount = tabCount,
            coroutineScope = coroutineScope,
        )
    }
}
