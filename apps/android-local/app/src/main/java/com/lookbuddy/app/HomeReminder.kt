package com.lookbuddy.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.Flow
import kotlin.math.sin

internal enum class HomeReminderKind(val backendKey: String, val label: String, val title: String, val message: String) {
    Drink("drink", "饮水提醒", "喝口水吧", "手边有水的话，顺便喝两口吧。"),
    Fatigue("fatigue", "疲劳提醒", "眼睛累了吗", "歇一小会儿吧，想聊聊的话我听着。"),
    Posture("posture", "调整姿态", "坐舒服一点", "肩膀松一松，换个舒服的坐姿吧。"),
    Stand("stand", "起身休息", "起来走走吧", "起来走两步？给身体换个节奏。"),
    Happy("happy", "开心表情陪伴", "开心传给你", "有什么开心的小事吗？想听你说说。"),
    Worried("worried", "担忧表情陪伴", "我在这里呢", "想找个地方说说话吗？我听着。"),
    MorningGreeting("morning_unknown", "早间问候", "早呀", "按你的节奏慢慢开始。"),
    EveningGreeting("evening", "晚间问候", "晚上好", "忙到间隙时，也给自己留点时间。"),
    DiaryReady("diary_ready", "工位日记", "今天的小记录", "工位日记准备好了，要去看看吗？");

    val opensChat: Boolean get() = this in setOf(Fatigue, Happy, Worried)
    val isGreeting: Boolean get() = this == MorningGreeting || this == EveningGreeting
    val buttonLabel: String? get() = when {
        isGreeting -> null
        this == DiaryReady -> "去看看"
        opensChat -> "聊一聊"
        else -> "好的"
    }
    val usesDefaultArtwork: Boolean get() = this == EveningGreeting || this == DiaryReady

    companion object {
        fun fromBackendKey(key: String): HomeReminderKind? = when (key) {
            "sedentary" -> Stand
            "morning_seated" -> MorningGreeting
            "joyful", "good", "positive_expression" -> Happy
            "possibly_stressed", "possibly_low", "possibly_angry", "concern_expression" -> Worried
            else -> entries.firstOrNull { it.backendKey == key }
        }
    }
}

/** Stable IDs let a future backend replay its latest event without reopening an acknowledged reminder. */
internal data class HomeReminderEvent(
    val id: String,
    val kind: HomeReminderKind,
    val title: String = kind.title,
    val message: String = kind.message
)

/** Future transport boundary. Preview and backend events use the same controller and renderer. */
internal interface HomeReminderSource {
    val reminders: Flow<HomeReminderEvent>
    suspend fun acknowledge(id: String) {}
}

@Stable
internal class HomeReminderController {
    var active by mutableStateOf<HomeReminderEvent?>(null)
        private set
    var finished by mutableStateOf(false)
        private set
    private var previewSerial = 0L
    private val acknowledged = linkedSetOf<String>()

    fun receive(event: HomeReminderEvent) {
        if (event.id.isBlank() || event.id in acknowledged || active?.id == event.id) return
        active = event
        finished = false
    }
    fun preview(kind: HomeReminderKind) {
        previewSerial++
        receive(HomeReminderEvent("preview:$previewSerial:${kind.backendKey}", kind))
    }
    fun finish(id: String) { if (active?.id == id) finished = true }
    fun acknowledge(): String? {
        val id = active?.id ?: return null
        acknowledged.add(id)
        if (acknowledged.size > 128) acknowledged.remove(acknowledged.first())
        active = null
        finished = false
        return id
    }

    companion object {
        val Saver = listSaver<HomeReminderController, String>(
            save = { state -> listOf(state.previewSerial.toString(), state.finished.toString(),
                state.active?.id.orEmpty(), state.active?.kind?.name.orEmpty(), state.active?.title.orEmpty(),
                state.active?.message.orEmpty()) + state.acknowledged },
            restore = { values -> HomeReminderController().apply {
                previewSerial = values[0].toLong()
                finished = values[1].toBoolean()
                if (values[2].isNotEmpty()) active = HomeReminderEvent(values[2], HomeReminderKind.valueOf(values[3]), values[4], values[5])
                acknowledged.addAll(values.drop(6))
            } }
        )
    }
}

@Composable
internal fun rememberHomeReminderController() = rememberSaveable(saver = HomeReminderController.Saver) { HomeReminderController() }

/** Positions are in the full 393×852 Figma canvas, including the status bar. */
@Composable
internal fun HomeBuddyArtwork(
    controller: HomeReminderController,
    workPlayback: WorkBuddyPlayback,
    night: Boolean,
    s: Float,
    verticalScale: Float
) {
    val event = controller.active
    if (event == null || event.kind.usesDefaultArtwork) {
        if (night) {
            Image(painterResource(R.drawable.home_buddy_night), "LookBuddy 回家休息",
                Modifier.offset(x = (-58).dp * s, y = 176.dp * verticalScale)
                    .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(514.dp * s, 369.dp * s),
                contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.offset(x = 39.dp * s, y = 226.dp * verticalScale)
                .size(301.77.dp * s, 262.01.dp * s).clipToBounds().testTag("buddy_normal")) {
                val run = workPlayback.generation
                BuddyWebpOnce(BuddyClip.Work, description = "LookBuddy 在电脑前办公",
                    modifier = Modifier.offset(x = 18.07.dp * s).size(265.63.dp * s, 262.01.dp * s),
                    run = run, completed = workPlayback.completed, onFinished = { workPlayback.finish(run) })
            }
        }
    } else key(event.id) {
        Box(Modifier.offset(y = 257.dp * verticalScale)
            .size(393.dp * s, 248.dp * s).clipToBounds().testTag("buddy_reminder_${event.kind.backendKey}")) {
            ReminderArtwork(event, controller.finished, s) { controller.finish(event.id) }
        }
    }
}

/** Scene-to-asset mapping is centralized; original WebP bytes stay unchanged. */
internal data class ReminderArtworkSpec(
    val clip: BuddyClip, val sourceWidth: Float, val sourceHeight: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

internal val ReminderArtworkHeight = 192.71484f // Current drink: 550 * 224.25 / 640.

internal fun reminderArtworkSpec(kind: HomeReminderKind): ReminderArtworkSpec? = when (kind) {
    HomeReminderKind.Drink -> ReminderArtworkSpec(BuddyClip.Drink, 1120.0f, 640.0f, 306.0f, 2.0f, 814.0f, 552.0f)
    HomeReminderKind.Stand -> ReminderArtworkSpec(BuddyClip.Stretch, 1120.0f, 640.0f, 272.0f, 11.0f, 848.0f, 616.0f)
    HomeReminderKind.Posture -> ReminderArtworkSpec(BuddyClip.Posture, 854.0f, 640.0f, 0.0f, 116.0f, 718.0f, 560.0f)
    HomeReminderKind.Fatigue -> ReminderArtworkSpec(BuddyClip.Fatigue, 854.0f, 640.0f, 38.0f, 57.0f, 731.0f, 640.0f)
    HomeReminderKind.Happy -> ReminderArtworkSpec(BuddyClip.Happy, 854.0f, 640.0f, 152.0f, 28.0f, 696.0f, 607.0f)
    HomeReminderKind.Worried -> ReminderArtworkSpec(BuddyClip.Concern, 854.0f, 640.0f, 137.0f, 30.0f, 685.0f, 605.0f)
    HomeReminderKind.MorningGreeting -> ReminderArtworkSpec(BuddyClip.Morning, 1120.0f, 640.0f, 285.0f, 28.0f, 841.0f, 605.0f)
    else -> null
}

@Composable
private fun ReminderArtwork(event: HomeReminderEvent, completed: Boolean, s: Float, onFinished: () -> Unit) {
    val spec = reminderArtworkSpec(event.kind) ?: return
    val scale = ReminderArtworkHeight / (spec.bottom - spec.top)
    val visibleWidth = (spec.right - spec.left) * scale
    // Center on the existing drink Buddy. Wide desk/sofa scenes remain inside the screen.
    val x = minOf(265.98875f - visibleWidth / 2f, 381f - visibleWidth).coerceAtLeast(0f)
    Box(Modifier.offset(x = x.dp * s, y = 3.24078.dp * s)
        .size(visibleWidth.dp * s, ReminderArtworkHeight.dp * s).clipToBounds()
        .testTag("reminder_visible_artwork")) {
        BuddyWebpOnce(spec.clip, description = "LookBuddy ${event.kind.label}", completed = completed,
            modifier = Modifier.offset(x = (-spec.left * scale).dp * s, y = (-spec.top * scale).dp * s)
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .requiredSize((spec.sourceWidth * scale).dp * s, (spec.sourceHeight * scale).dp * s),
            contentScale = ContentScale.Fit, onFinished = onFinished)
    }
}

@Composable
internal fun HomeReminderBubble(
    controller: HomeReminderController,
    hidden: Boolean,
    s: Float,
    verticalScale: Float,
    onAcknowledged: (String) -> Unit,
    onChat: (HomeReminderEvent) -> Unit = {},
    onDiary: () -> Unit = {}
) {
    val event = controller.active
    LaunchedEffect(event?.id) {
        if (event?.kind?.isGreeting == true) {
            kotlinx.coroutines.delay(6000)
            if (controller.active?.id == event.id) controller.acknowledge()?.let(onAcknowledged)
        }
    }
    var last by remember { mutableStateOf(event) }
    SideEffect { if (event != null) last = event }
    val fontScale = LocalDensity.current.fontScale
    // Keep Figma's height at standard font size; grow downward for accessibility while staying in the left lane.
    val h = 155f + (fontScale - 1f).coerceAtLeast(0f) * 95f
    AnimatedVisibility(event != null && !hidden, enter = fadeIn(tween(200)), exit = fadeOut(tween(200)),
        modifier = Modifier.offset(x = 22.dp * s, y = 198.dp * verticalScale)) {
        val display = event ?: last
        if (display != null) Column(Modifier.width(162.dp * s).heightIn(min = h.dp * s)
            .clip(RoundedCornerShape(70.dp * s)).background(Color.White.copy(alpha = .48f))
            .testTag("home_reminder_bubble").padding(15.dp * s),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp * s)) {
            Text(display.title, color = CompanionInk, fontSize = (18.75f * s).sp, lineHeight = (27.5f * s).sp,
                textAlign = TextAlign.Center, modifier = Modifier.width(120.dp * s))
            Text(display.message, color = CompanionMuted, fontSize = (15f * s).sp, lineHeight = (22.5f * s).sp,
                textAlign = TextAlign.Center, modifier = Modifier.width(120.dp * s))
            if (display.kind.buttonLabel != null) Box(Modifier.width(104.dp * s).heightIn(min = maxOf(44.dp, 44.dp * s))
                .clip(RoundedCornerShape(22.dp * s)).background(Color.White.copy(alpha = .48f))
                .clickable(enabled = event?.id == display.id && !hidden, role = Role.Button) {
                    val current = controller.active
                    controller.acknowledge()?.let(onAcknowledged)
                    if (current?.kind?.opensChat == true) onChat(current)
                    if (current?.kind == HomeReminderKind.DiaryReady) onDiary()
                }.testTag("acknowledge_reminder"), contentAlignment = Alignment.Center) {
                Text(display.kind.buttonLabel.orEmpty(), color = CompanionInk, fontSize = (15f * s).sp, lineHeight = (22f * s).sp)
            }
        }
    }
}

@Composable
internal fun ReminderPreviewDialog(controller: HomeReminderController, onClose: () -> Unit, onNormal: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, containerColor = CompanionPaper, title = { Text("预览首页状态") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf<HomeReminderKind?>(null).plus(HomeReminderKind.entries).forEach { kind ->
                val selected = controller.active?.kind == kind
                Text(if (kind == null) "普通状态（无提醒）" else kind.label, color = if (selected) CompanionClay else CompanionInk,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics { this.selected = selected }.clickable(role = Role.RadioButton) {
                            if (kind == null) onNormal() else controller.preview(kind)
                            onClose()
                        }.padding(vertical = 12.dp, horizontal = 8.dp).testTag("preview_" + (kind?.backendKey ?: "normal")))
            }
        } }, confirmButton = { TextButton(onClick = onClose) { Text("关闭", color = CompanionClay) } })
}
