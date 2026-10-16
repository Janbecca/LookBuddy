package com.lookbuddy.app

import android.content.res.AssetManager
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.caverock.androidsvg.RenderOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlin.math.abs

private val HomeInk = Color(0xFF38291F)
private val HomeMuted = Color(0xFF756354)
private val HomeCard = Color(0xFFFBF7F1)
internal val LocalHomeSvgLoader = staticCompositionLocalOf<(AssetManager, String) -> SVG> {
    { assets, filename -> SVG.getFromAsset(assets, filename) }
}

/** Follow the finger without launching a new animation for every drag delta. */
@Stable
private class StatisticsDrawerMotion(initialExpanded: Boolean, private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(if (initialExpanded) 1f else 0f)
        private set
    private var settling: Job? = null

    fun stop() { settling?.cancel(); settling = null }

    fun dragBy(deltaY: Float, range: Float): Float {
        stop()
        val previous = progress
        progress = (progress - deltaY / range).coerceIn(0f, 1f)
        return (previous - progress) * range
    }

    fun settle(velocityY: Float, range: Float, onTarget: (Boolean) -> Unit) {
        val open = if (abs(velocityY) > range * 1.5f) velocityY < 0 else progress >= .5f
        settleTo(open, onTarget)
    }

    fun settleTo(open: Boolean, onTarget: (Boolean) -> Unit) {
        stop()
        onTarget(open)
        settling = scope.launch {
            animate(progress, if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = 1f, stiffness = 400f)) { value, _ ->
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

/** The morning Figma screen and its statistics drawer share the same original assets. */
@Composable
internal fun MorningHomeContent(
    initialExpanded: Boolean = false,
    onCalendar: () -> Unit = {},
    greetingScene: String? = "morning_seated",
    onCamera: () -> Unit = {},
    onDiary: () -> Unit = {},
    onSettings: () -> Unit = {},
    now: java.time.LocalDateTime = rememberAppTime(),
    schedule: WorkSchedule? = null,
    reminders: HomeReminderController? = null,
    onReminderAcknowledged: (String) -> Unit = {},
    onReminderChat: (HomeReminderEvent) -> Unit = {},
    onReminderRespond: (String, HomeReminderResponse) -> Unit = { _, _ -> },
    onReminderDiary: (HomeReminderEvent) -> Unit = { onDiary() },
    desktopSyncVisible: Boolean = true
) {
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    val workPlayback = LocalWorkPlayback.current ?: rememberWorkBuddyPlayback()
    val controller = reminders ?: rememberHomeReminderController()
    var previewOpen by rememberSaveable { mutableStateOf(false) }
    val product = LocalProduct.current
    val accountId = product?.user?.optString("id")
    var cloudSchedule by remember(product, accountId) { mutableStateOf<WorkSchedule?>(null) }
    var scheduleError by remember(product, accountId) { mutableStateOf(false) }
    LaunchedEffect(product, accountId, product?.summary) {
        if (product != null && accountId != null) {
            try {
                val value = JSONObject(product.request("/v1/work-schedule"))
                cloudSchedule = WorkSchedule(java.time.LocalTime.parse(value.getString("start")),
                    java.time.LocalTime.parse(value.getString("end")))
                scheduleError = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { scheduleError = true }
        }
    }
    val localSchedule = if (product == null && schedule == null) rememberWorkSchedule() else null
    val status = (if (product != null) cloudSchedule else schedule ?: localSchedule)?.status(now)
    val night = isNightTime(now.toLocalTime())
    val working = if (product != null) product.current != null else status?.working == true
    val elapsed = if (product != null) sessionElapsedMinutes(product.current, now,
        product.user?.optString("timezone") ?: java.time.ZoneId.systemDefault().id) else status?.elapsedMinutes
    val context = LocalContext.current
    val inspection = androidx.compose.ui.platform.LocalInspectionMode.current
    val greeting = if (greetingScene != null) rememberBuddyGreeting(scene = greetingScene).collectAsState().value else BuddyGreeting.Idle
    val companionDays = if (product != null) product.summary.observedLong("companionDays")?.toString() ?: "--"
        else remember(now.toLocalDate(), inspection) { if (inspection) "1" else WorkScheduleStore(context).companionDays(now.toLocalDate()).toString() }
    val scope = rememberCoroutineScope()
    val drawerMotion = remember { StatisticsDrawerMotion(expanded, scope) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().testTag("morning_home")) {
        val s = maxWidth.value / 393f
        val density = LocalDensity.current
        val topInset = with(density) { WindowInsets.statusBars.getTop(this).toDp().value }
        val bottomInset = maxOf(with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }, 34.dp * s)
        val navHeight = 56.dp * s
        val drawerProgress = drawerMotion.progress
        val drawerHeight = (183f + 255f * drawerProgress).dp * s

        Image(painterResource(if (night) R.drawable.home_room_night else R.drawable.home_room), null,
            Modifier.fillMaxSize().testTag(if (night) "night_background" else "day_background"), contentScale = ContentScale.Crop)
        if (drawerProgress < 1f) {
            FigmaSvg("top_curve", s, Modifier.offset(x = (310.55f * s).dp, y = (-417.4f * s).dp)
                .graphicsLayer { alpha = 1f - drawerProgress })
        }
        // Preserve sync's uniform scene scaling and lower the Buddy/bubble group together.
        val drawerTop = maxHeight.value - navHeight.value - bottomInset.value - 183f * s
        val sceneScale = minOf(s, ((drawerTop - 184f * s) / 330f).coerceAtLeast(.35f))
        val quoteHasRoom = controller.active == null || (density.fontScale <= 1.1f && maxHeight.value >= 760f * s)
        val sceneOrigin = 184f * (s - sceneScale)
        val artworkBottom = when {
            controller.active?.kind?.usesDefaultArtwork == false -> 453f
            night -> 545f
            else -> 488.01f
        }
        val lowerLimit = drawerTop - if (quoteHasRoom) (48f * s + 16f * sceneScale) else 16f * s
        val sceneDrop = minOf(32f * s, (lowerLimit - sceneOrigin - artworkBottom * sceneScale).coerceAtLeast(0f))
        Box(Modifier.fillMaxSize().offset(x = ((maxWidth.value - 393f * sceneScale) / 2f).dp,
            y = (sceneOrigin + sceneDrop).dp).testTag("home_ip_bubble_group")) {
            HomeBuddyArtwork(controller, workPlayback, night, sceneScale, sceneScale)
            HomeReminderBubble(controller, drawerProgress > .001f, sceneScale, sceneScale,
                onReminderAcknowledged, onReminderChat, onDiary, onReminderRespond, onReminderDiary)
            // Keep main's authenticated greeting, positioned with the new scene and hidden by the drawer.
            if (greetingScene != null) {
                if (greeting.show && greeting.message.isNotBlank() && controller.active == null && drawerProgress <= .001f) {
                    BuddySpeechBubble(greeting.message, sceneScale,
                        Modifier.offset(x = 22.dp * sceneScale, y = 198.dp * sceneScale))
                }
            }
        }
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Spacer(Modifier.height((62f * s - topInset).coerceAtLeast(0f).dp))
            Row(Modifier.fillMaxWidth().height(51.dp * s).padding(horizontal = 16.dp * s),
                verticalAlignment = Alignment.CenterVertically) {
                Text("LOOKBuddy", color = Color.Black, fontSize = (24 * s).sp, modifier = Modifier.weight(1f))
                Row(Modifier.offset(y = 8.dp * s), horizontalArrangement = Arrangement.spacedBy(8.dp * s)) {
                    HeaderIcon("calendar_circle", "calendar", s, "心情日历", onCalendar)
                    HeaderIcon("camera_circle", "camera", s, "陪伴视角", onCamera)
                    Text("预览", color = HomeInk, fontSize = (12 * s).sp,
                        modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = .48f)).clickable(role = Role.Button) { previewOpen = true }
                            .padding(horizontal = 12.dp * s, vertical = 12.dp * s).testTag("home_state_preview"))
                }
            }
            Row(Modifier.align(Alignment.CenterHorizontally).width(200.dp * s).height(38.dp * s)
                .clip(RoundedCornerShape(66.dp)).background(Color.White.copy(alpha = 0.1f))) {
                listOf(true, false).forEach { isWork ->
                    Row(Modifier.weight(1f).fillMaxHeight()
                        .background(if (working == isWork) BuddyCream.copy(alpha = 0.6f) else Color.Transparent,
                            RoundedCornerShape(28.dp))
                        .then(if (product != null) Modifier.clickable(enabled = !product.busy && working != isWork, role = Role.Button) {
                            if (isWork) product.startWork() else product.finishWork()
                        } else Modifier)
                        .semantics {
                            this.selected = working == isWork
                            if (product != null) contentDescription = if (isWork) "开始实际陪伴" else "结束实际陪伴"
                        }.testTag(if (isWork) "work_mode" else "offwork_mode"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        FigmaSvg(if (isWork) "work" else "offwork", s)
                        Spacer(Modifier.width(6.dp * s))
                        Text(if (product != null) {
                            if (isWork) { if (working) "陪伴中" else "开始陪伴" }
                            else { if (working) "结束陪伴" else "未陪伴" }
                        } else if (isWork) "上班中" else "下班喽~", color = HomeInk, fontSize = (14 * s).sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(32.dp * s).padding(horizontal = 11.dp * s),
                verticalAlignment = Alignment.CenterVertically) {
                WorkTime(if (product != null) "已陪伴" else "已上班", elapsed?.let(::displayDuration) ?: "--", s)
                Box(Modifier.weight(1f).padding(horizontal = 5.dp * s).height(13.dp * s)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(8.dp))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(status?.progress ?: 0f).background(BuddyCream.copy(alpha = .6f)))
                }
                WorkTime("距下班时间", status?.remainingMinutes?.let(::displayDuration) ?: "--", s)
            }
            if (controller.active?.id?.startsWith("preview:") == true) Text("本地提醒预览", color = HomeMuted,
                fontSize = (11 * s).sp, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("home_preview_label"))
            if (product?.busy == true) Text("同步中…", color = HomeMuted, fontSize = (11 * s).sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).testTag("home_loading"))
            if (scheduleError) {
                Text("工作时段读取失败，请重试", color = CompanionClay, fontSize = (11 * s).sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).testTag("home_schedule_error"))
            }
        }
        if (greetingScene != null && desktopSyncVisible) {
            val event = controller.active
            val message = if (drawerProgress <= .001f) event?.message ?: greeting.message.takeIf { greeting.show } else ""
            DesktopPetSyncEffect(
                scene = event?.kind?.backendKey ?: if (night) "evening" else "idle",
                working = working,
                message = message,
                eventId = event?.id ?: "home:${if (night) "night" else "day"}:${workPlayback.generation}",
                defaultScene = if (night) "evening" else "idle",
                completed = if (event != null && !event.kind.usesDefaultArtwork) controller.finished else night || workPlayback.completed
            )
        }
        if (drawerProgress < 1f && quoteHasRoom) {
            Text("一起陪伴的第${companionDays}天，也要照顾好自己~", color = HomeInk, fontSize = (16 * s).sp,
                letterSpacing = (2.2f * s).sp, lineHeight = (30 * s).sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { alpha = 1f - drawerProgress }
                    .padding(bottom = navHeight + bottomInset + drawerHeight + 18.dp * s)
                    .fillMaxWidth().padding(horizontal = 20.dp * s).testTag("home_companion_quote"))
        }
        StatisticsDrawer(expanded, drawerMotion, s, Modifier.align(Alignment.BottomCenter)
            .padding(bottom = navHeight + bottomInset).height(drawerHeight), now.toLocalDate()) { expanded = it }
        CompanionNavigation(CompanionPage.Home, s, onPage = { destination ->
            when (destination) {
                CompanionPage.Camera -> onCamera()
                CompanionPage.Diary -> onDiary()
                CompanionPage.Settings -> onSettings()
                CompanionPage.Home -> Unit
            }
        }, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (previewOpen) ReminderPreviewDialog(controller, onClose = { previewOpen = false }, onNormal = {
        controller.acknowledge()?.let(onReminderAcknowledged)
    })
}

/** Missing summary fields must not silently become zero-valued measurements. */
internal fun JSONObject?.observedLong(key: String): Long? = (this?.opt(key) as? Number)?.toLong()?.takeIf { it >= 0 }

internal fun sessionElapsedMinutes(session: JSONObject?, now: java.time.LocalDateTime, zone: String): Long? {
    val started = session?.optString("startedAt")?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val begin = runCatching { java.time.OffsetDateTime.parse(started).toInstant() }
            .getOrElse { java.time.LocalDateTime.parse(started).toInstant(java.time.ZoneOffset.UTC) }
        java.time.Duration.between(begin, now.atZone(java.time.ZoneId.of(zone)).toInstant()).toMinutes().coerceAtLeast(0)
    }.getOrNull()
}

@Composable
private fun HeaderIcon(circle: String, icon: String, s: Float, description: String, onClick: (() -> Unit)? = null) {
    Box(Modifier.size(37.dp * s, 36.dp * s)
        .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
        .semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        FigmaSvg(circle, s)
        FigmaSvg(icon, s)
    }
}

@Composable
private fun WorkTime(label: String, value: String, s: Float) {
    Column(Modifier.width(53.dp * s), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = HomeInk, fontSize = (8 * s).sp, lineHeight = (11 * s).sp)
        Text(value, color = HomeInk, fontSize = (12 * s).sp, lineHeight = (17 * s).sp)
    }
}

@Composable
private fun StatisticsDrawer(
    expanded: Boolean,
    motion: StatisticsDrawerMotion,
    s: Float,
    modifier: Modifier,
    today: java.time.LocalDate,
    onExpand: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(topStart = 20.dp * s, topEnd = 20.dp * s)
    val latestExpand by rememberUpdatedState(onExpand)
    val range = with(LocalDensity.current) { (255.dp * s).toPx() }
    val scroll = rememberScrollState()
    val showDetails = expanded || motion.progress > .001f
    val gesture = remember(motion, range, scroll) {
        object : NestedScrollConnection {
            var draggingSheet = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                // Open before scrolling content; close only once content is back at its top.
                if ((available.y < 0 && motion.progress < 1f) ||
                    (available.y > 0 && scroll.value == 0 && motion.progress > 0f)) {
                    val consumed = motion.dragBy(available.y, range)
                    draggingSheet = draggingSheet || consumed != 0f
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0 && scroll.value == 0 && motion.progress > 0f) {
                    val moved = motion.dragBy(available.y, range)
                    draggingSheet = draggingSheet || moved != 0f
                    return Offset(0f, moved)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!draggingSheet) return Velocity.Zero
                draggingSheet = false
                motion.settle(available.y, range, latestExpand)
                return Velocity(0f, available.y)
            }
        }
    }
    Box(modifier.fillMaxWidth().testTag("statistics_sheet")) {
    // Only the outer edge casts a shadow; the transparent tray keeps the room visible beneath it.
    Box(Modifier.matchParentSize().drawWithContent {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply {
            when (outline) {
                is Outline.Rounded -> addRoundRect(outline.roundRect)
                is Outline.Rectangle -> addRect(outline.rect)
                is Outline.Generic -> addPath(outline.path)
            }
        }
        clipPath(path, ClipOp.Difference) { this@drawWithContent.drawContent() }
    }.dropShadow(shape, Shadow(radius = 4.dp * s, color = Color.Black.copy(alpha = 0.25f),
        offset = DpOffset(4.dp * s, 0.dp))))
    Column(Modifier.fillMaxSize().clip(shape)
        .background(Color.White.copy(alpha = .5f + .25f * motion.progress))
        .testTag(if (showDetails) "expanded_stats" else "collapsed_stats")
        .nestedScroll(gesture).padding(horizontal = 20.dp * s)) {
        Column(Modifier.fillMaxWidth().height((22f + 44f * motion.progress).dp * s)
            .draggable(state = rememberDraggableState { motion.dragBy(it, range) },
                orientation = Orientation.Vertical,
                onDragStarted = { motion.stop() },
                onDragStopped = { motion.settle(it, range, latestExpand) })
            .then(if (!showDetails) Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button
            ) { motion.settleTo(true, latestExpand) } else Modifier)
            .semantics { contentDescription = if (showDetails) "下滑收起统计便当" else "展开统计便当" }
            .testTag("stats_toggle"), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height((8f + motion.progress).dp * s))
            Box(Modifier.size(35.dp * s, 3.dp * s).background(Color(0xFFDED6CC), RoundedCornerShape(2.dp)))
            if (showDetails) {
                Spacer(Modifier.height(14.dp * s))
                Row(Modifier.fillMaxWidth().height(28.dp * s), verticalAlignment = Alignment.CenterVertically) {
                    Text("今日陪伴", color = HomeInk, fontSize = (20 * s).sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).testTag("stats_content")) {
            PrimaryStatistics(s)
            if (showDetails) {
                Spacer(Modifier.height(12.dp * s))
                SupplementaryStatistics(s)
                Spacer(Modifier.height(12.dp * s))
                Row(Modifier.fillMaxWidth().height(64.dp * s)
                    .background(Color(0xFFFFF5EA), RoundedCornerShape(18.dp)).padding(horizontal = 16.dp * s),
                    verticalAlignment = Alignment.CenterVertically) {
                    FigmaSvg("sun", s)
                    Spacer(Modifier.width(10.dp * s))
                    Text("距周末还有", color = Color(0xFFAB5E3B), fontSize = (14 * s).sp, modifier = Modifier.weight(1f))
                    Text(daysUntilWeekend(today).toString(), color = Color(0xFFAB5E3B), fontSize = (30 * s).sp, fontWeight = FontWeight.Bold)
                    Text(" 天", color = Color(0xFFAB5E3B), fontSize = (14 * s).sp)
                }
                Spacer(Modifier.height(24.dp * s))
            } else Spacer(Modifier.height(9.dp * s))
        }
    }
    }
}

@Composable
private fun PrimaryStatistics(s: Float) {
    val summary = LocalProduct.current?.summary
    Row(Modifier.fillMaxWidth().height(152.dp * s), horizontalArrangement = Arrangement.spacedBy(12.dp * s)) {
        Column(Modifier.width(104.dp * s).fillMaxHeight().background(HomeCard, RoundedCornerShape(18.dp)),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            IconTile("focus", Color(0xFFFFF1E5), 36f, 12f, s)
            Spacer(Modifier.height(10.dp * s))
            Text("工作陪伴", color = HomeMuted, fontSize = (12 * s).sp)
            Spacer(Modifier.height(10.dp * s))
            Text(summary.observedLong("workMs")?.let { displayDuration(it / 60000) } ?: "--", color = HomeInk,
                fontSize = (18 * s).sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("home_work_duration"))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp * s)) {
            Row(Modifier.fillMaxWidth().height(46.dp * s).background(Color(0xFFFFF5EA), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp * s), verticalAlignment = Alignment.CenterVertically) {
                Text("今日状态", color = Color(0xFF7A634F), fontSize = (12 * s).sp, modifier = Modifier.weight(1f))
                FigmaSvg("fire", s)
                Spacer(Modifier.width(7.dp * s))
                Text("暂无数据", color = Color(0xFF9E5436), fontSize = (14 * s).sp)
            }
            Row(Modifier.fillMaxWidth().height(96.dp * s), horizontalArrangement = Arrangement.spacedBy(10.dp * s)) {
                SmallStatistic("tea", "今日饮水", summary.observedLong("drinkCount")?.let { "$it 次" } ?: "--", Color(0xFFECF4F4), s, Modifier.weight(1f))
                SmallStatistic("stretch", "伸展次数", summary.observedLong("stretchCount")?.let { "$it 次" } ?: "--", Color(0xFFF1F4E8), s, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SmallStatistic(icon: String, title: String, value: String, tile: Color, s: Float, modifier: Modifier) {
    Column(modifier.fillMaxHeight().background(HomeCard, RoundedCornerShape(18.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp * s, Alignment.CenterVertically)) {
        IconTile(icon, tile, 28f, 9f, s)
        Text(title, color = HomeMuted, fontSize = (12 * s).sp)
        Text(value, color = HomeInk, fontSize = (19 * s).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun IconTile(icon: String, color: Color, size: Float, radius: Float, s: Float) {
    Box(Modifier.size((size * s).dp).background(color, RoundedCornerShape(radius.dp)), contentAlignment = Alignment.Center) {
        FigmaSvg(icon, s)
    }
}

@Composable
private fun SupplementaryStatistics(s: Float) {
    val summary = LocalProduct.current?.summary
    Row(Modifier.fillMaxWidth().height(108.dp * s), horizontalArrangement = Arrangement.spacedBy(12.dp * s)) {
        Column(Modifier.weight(1f).fillMaxHeight().background(HomeCard, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp * s, vertical = 12.dp * s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FigmaSvg("month", s)
                Spacer(Modifier.width(6.dp * s))
                Text("本月工作时长", color = Color(0xFF7A6657), fontSize = (12 * s).sp)
            }
            Spacer(Modifier.height(12.dp * s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(summary.observedLong("monthWorkMs")?.let { "%.1f".format(it / 3600000.0) } ?: "--", color = HomeInk, fontSize = (32 * s).sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(7.dp * s))
                Text("小时", color = Color(0xFF7A6657), fontSize = (13 * s).sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp * s)) {
            Row(Modifier.fillMaxWidth().height(49.dp * s).background(Color(0xFFF1F6EB), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp * s), verticalAlignment = Alignment.CenterVertically) {
                Text("今日坐姿", color = Color(0xFF7A6657), fontSize = (12 * s).sp, modifier = Modifier.weight(1f))
                val state = summary?.optJSONObject("state")
                Text(if (state?.optBoolean("fresh") == true && state.optString("posture") == "bad") "可以调整一下" else "暂无数据", color = Color(0xFF738C57), fontSize = (14 * s).sp)
            }
            Row(Modifier.fillMaxWidth().height(49.dp * s).background(Color(0xFFF0F6F7), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp * s), verticalAlignment = Alignment.CenterVertically) {
                FigmaSvg("moon", s)
                Spacer(Modifier.width(6.dp * s))
                Text("今日小憩", color = Color(0xFF7A6657), fontSize = (12 * s).sp, modifier = Modifier.weight(1f))
                Text(summary.observedLong("breakMs")?.let { "${it / 60000}min" } ?: "--", color = HomeInk, fontSize = (16 * s).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Buddy IP 旁的 GLM 关心语气泡。
 *
 * 半透明白底圆角气泡，带小尾巴指向角色。文案为模型生成或兜底文案。
 * 不使用 emoji 或 Markdown，与首页风格一致。
 */
@Composable
private fun BuddySpeechBubble(
    message: String,
    s: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .width(162.dp * s)
            .clipToBounds()
            .semantics { contentDescription = message }
            .testTag("buddy_greeting_bubble")
    ) {
        // 气泡主体（半透明白底，带圆角和阴影感）
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(30.dp * s))
                .background(Color.White.copy(alpha = 0.64f))
                .padding(horizontal = 18.dp * s, vertical = 20.dp * s)
        ) {
            Text(
                message,
                color = HomeInk,
                fontSize = (15 * s).sp,
                lineHeight = (24 * s).sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Parse the original SVG asset, keeping its authored root dimensions and paths unchanged. */
@Composable
internal fun FigmaSvg(name: String, s: Float, modifier: Modifier = Modifier, folder: String = "home") {
    val context = LocalContext.current
    val loader = LocalHomeSvgLoader.current
    val svg = remember(name, folder, loader) { loader(context.assets, "$folder/$name.svg") }
    val width = svg.documentWidth
    val height = svg.documentHeight
    Canvas(modifier.requiredSize((width * s).dp, (height * s).dp).semantics { contentDescription = name }) {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(size.width / width, size.height / height)
            svg.renderToCanvas(native, RenderOptions.create().viewPort(0f, 0f, width, height))
            native.restore()
        }
    }
}
