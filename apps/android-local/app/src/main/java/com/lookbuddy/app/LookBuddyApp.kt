package com.lookbuddy.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal val CompanionPaper = Color(0xFFFEFBF6)
internal val CompanionInk = Color(0xFF40362B)
internal val CompanionMuted = Color(0xFF918270)
internal val CompanionBorder = Color(0xFFE8DCCB)
internal val CompanionClay = Color(0xFFA86843)

internal enum class CompanionPage { Home, Diary, Settings, Camera }

/** One injectable camera boundary; page code never depends on a camera vendor or transport. */
@Composable
internal fun LookBuddyApp(
    initialPage: CompanionPage = CompanionPage.Home,
    cameraFeed: CameraFeed? = null
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val defaultCamera = remember { UnconnectedCameraFeed() }
    val camera = cameraFeed ?: defaultCamera
    val savedPages = rememberSaveableStateHolder()
    val workPlayback = rememberWorkBuddyPlayback()
    BackHandler(page != CompanionPage.Home) { page = CompanionPage.Home }
    CompositionLocalProvider(LocalWorkPlayback provides workPlayback) {
    savedPages.SaveableStateProvider(page.name) {
        when (page) {
            CompanionPage.Home -> MorningHomeScreen(onCamera = { page = CompanionPage.Camera },
                onDiary = { page = CompanionPage.Diary }, onSettings = { page = CompanionPage.Settings })
            else -> CompanionPageLayout(page, onPage = { page = it }) { s ->
                when (page) {
                    CompanionPage.Diary -> DiaryScreen(s)
                    CompanionPage.Camera -> CameraScreen(camera, s)
                    CompanionPage.Settings -> PersonalSettingsScreen(s, onCamera = { page = CompanionPage.Camera })
                    else -> Unit
                }
            }
        }
    }
    }
}

@Composable
internal fun CompanionPageLayout(
    page: CompanionPage,
    onPage: (CompanionPage) -> Unit = {},
    content: @Composable ColumnScope.(Float) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CompanionPaper)) {
        val s = (maxWidth.value / 393f).coerceAtMost(1.35f)
        val topInset = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Spacer(Modifier.height((62.dp * s - topInset).coerceAtLeast(0.dp)))
            Column(Modifier.weight(1f).fillMaxWidth()) { content(s) }
            CompanionNavigation(page, s, onPage)
        }
    }
}

/** Shared four-tab bar, matching the Figma diary screen. */
@Composable
internal fun CompanionNavigation(
    page: CompanionPage,
    s: Float,
    onPage: (CompanionPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val nativeBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
    Row(modifier.fillMaxWidth().background(Color.White)
        .padding(bottom = maxOf(nativeBottom, 34.dp * s)).height(56.dp * s)
        .padding(horizontal = 12.dp * s), verticalAlignment = Alignment.CenterVertically) {
        listOf(CompanionPage.Home, CompanionPage.Camera, CompanionPage.Diary, CompanionPage.Settings).forEach { destination ->
            val active = page == destination
            val label = when (destination) {
                CompanionPage.Home -> "首页"
                CompanionPage.Camera -> "陪伴视角"
                CompanionPage.Diary -> "陪伴日记"
                CompanionPage.Settings -> "个人设置"
            }
            val icon = destination.name.lowercase()
            val tint = if (active) CompanionClay else CompanionMuted
            Box(Modifier.weight(1f).height(50.dp * s).clickable(role = Role.Tab) { onPage(destination) }
                .semantics { contentDescription = label; selected = active }
                .testTag("nav_" + icon), contentAlignment = Alignment.Center) {
                // Figma SVG dimensions preserve the exact icon geometry inside its 26dp slot.
                Box(Modifier.size(26.dp * s), contentAlignment = Alignment.Center) {
                    FigmaSvg(icon, s, Modifier.graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }.drawWithContent {
                        drawContent()
                        drawRect(tint, blendMode = BlendMode.SrcIn)
                    }, folder = "navigation")
                }
            }
        }
    }
}
