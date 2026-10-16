package com.lookbuddy.app

import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
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

internal enum class CompanionPage { Home, Diary, Settings }

/** Reminder transport is injectable; preview events share the same state above all tabs. */
@Composable
internal fun LookBuddyApp(
    initialPage: CompanionPage = CompanionPage.Home,
    reminderSource: HomeReminderSource? = null
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    val reminders = rememberHomeReminderController()
    val chat = rememberBuddyChatState()
    var showChat by rememberSaveable { mutableStateOf(false) }
    val openChat: (String?) -> Unit = { opening -> chat.enter(opening); showChat = true }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(reminderSource, owner) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            reminderSource?.reminders?.collect { reminders.receive(it) }
        }
    }
    val acknowledge: (String) -> Unit = { id ->
        scope.launch { runCatching { reminderSource?.acknowledge(id) } }
    }
    val savedPages = rememberSaveableStateHolder()
    val workPlayback = rememberWorkBuddyPlayback()
    BackHandler(showChat || page != CompanionPage.Home) {
        if (showChat) showChat = false else page = CompanionPage.Home
    }
    CompositionLocalProvider(LocalWorkPlayback provides workPlayback) {
    if (showChat) {
        BuddyChatScreen(chat, onPage = { showChat = false; page = it })
    } else savedPages.SaveableStateProvider(page.name) {
        when (page) {
            CompanionPage.Home -> MorningHomeScreen(reminders = reminders, onReminderAcknowledged = acknowledge,
                onDiary = { page = CompanionPage.Diary }, onSettings = { page = CompanionPage.Settings },
                onChat = { openChat(null) },
                onReminderChat = { event -> openChat(event.message) })
            else -> CompanionPageLayout(page, onPage = { page = it }) { s ->
                when (page) {
                    CompanionPage.Diary -> DiaryScreen(s, onChat = { openChat("今天有什么想和我说说的吗？") })
                    CompanionPage.Settings -> PersonalSettingsScreen(s)
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

/** Three destinations: Home, Diary, Settings. Camera page was removed in 1.5. */
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
        listOf(CompanionPage.Home, CompanionPage.Diary, CompanionPage.Settings).forEach { destination ->
            val active = page == destination
            val label = when (destination) {
                CompanionPage.Home -> "首页"
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
