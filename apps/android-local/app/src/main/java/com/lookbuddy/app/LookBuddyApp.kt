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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val CompanionPaper = Color(0xFFFEFBF6)
internal val CompanionInk = Color(0xFF40362B)
internal val CompanionMuted = Color(0xFF918270)
internal val CompanionBorder = Color(0xFFE8DCCB)
internal val CompanionClay = Color(0xFFA86843)

internal enum class CompanionPage { Home, Diary, Settings, Camera }

/** Production HTTP reminders and injectable transports share the same renderer. */
@Composable
internal fun LookBuddyApp(
    initialPage: CompanionPage = CompanionPage.Home,
    cameraFeed: CameraFeed? = null,
    reminderSource: HomeReminderSource? = null
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var diaryDate by rememberSaveable { mutableStateOf<String?>(null) }
    val defaultCamera = remember { UnconnectedCameraFeed() }
    val camera = cameraFeed ?: defaultCamera
    val product = LocalProduct.current
    val accountIdentity = product?.diaryIdentity
    val source = reminderSource ?: remember(product, accountIdentity) {
        product?.takeIf { it.user != null }?.let { ReminderRepository(it, accountIdentity!!) }
    }
    val reminders = key(accountIdentity) { rememberHomeReminderController() }
    val context = LocalContext.current
    DisposableEffect(source, context) {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { (source as? ReminderRepository)?.refresh() }
        }
        val registered = source is ReminderRepository && runCatching {
            manager.registerDefaultNetworkCallback(callback)
        }.isSuccess
        onDispose { if (registered) runCatching { manager.unregisterNetworkCallback(callback) } }
    }
    val chat = key(accountIdentity) { rememberBuddyChatState() }
    var showChat by rememberSaveable(accountIdentity) { mutableStateOf(false) }
    val openChat: (String?) -> Unit = { opening -> chat.enter(opening); showChat = true }
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(source, owner, accountIdentity) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            source?.updates?.collect { reminders.update(it) }
        }
    }
    val acknowledge: (String) -> Unit = { id ->
        scope.launch {
            try { source?.acknowledge(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { android.util.Log.d("HomeReminder", "Reminder acknowledgement unavailable") }
        }
    }
    val respond: (String, HomeReminderResponse) -> Unit = { id, response ->
        if (reminders.beginResponse(id)) scope.launch {
            try {
                source?.respond(id, response) ?: error("提醒服务未连接")
                if (reminders.active?.id == id) reminders.acknowledge()
            } catch (e: CancellationException) {
                reminders.responseFailed(id, "提交已中断，请重试")
                throw e
            } catch (_: Exception) { reminders.responseFailed(id, "提交失败，请重试所选操作") }
        }
    }
    val savedPages = key(accountIdentity) { rememberSaveableStateHolder() }
    val workPlayback = rememberWorkBuddyPlayback()
    BackHandler(showChat || page != CompanionPage.Home) {
        if (showChat) showChat = false else page = CompanionPage.Home
    }
    CompositionLocalProvider(LocalWorkPlayback provides workPlayback) {
        if (showChat) {
            BuddyChatScreen(chat, onPage = { showChat = false; page = it })
        } else savedPages.SaveableStateProvider(page.name) {
            when (page) {
                CompanionPage.Home -> MorningHomeScreen(onCamera = { page = CompanionPage.Camera },
                    onDiary = { page = CompanionPage.Diary }, onSettings = { page = CompanionPage.Settings },
                    reminders = reminders, onReminderAcknowledged = acknowledge,
                    onReminderRespond = respond,
                    onReminderDiary = { event -> diaryDate = event.diaryDate?.toString(); page = CompanionPage.Diary },
                    onReminderChat = { event -> openChat(event.message) })
                else -> CompanionPageLayout(page, onPage = { page = it }) { s ->
                    when (page) {
                        CompanionPage.Diary -> DiaryScreen(s, onChat = { openChat("今天有什么想和我说说的吗？") },
                            initialDate = diaryDate?.let(java.time.LocalDate::parse))
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
