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
    Drink("drink", "饮水提醒", "喝口水吧", "歇一小会儿\n我陪你一起～"),
    Fatigue("fatigue", "疲劳提醒", "眼睛累了吗", "闭眼歇一歇\n我陪你放松～"),
    Posture("posture", "调整姿态", "坐舒服一点", "肩膀放轻松\n一起坐端正～"),
    Stand("stand", "起身休息", "起来走走吧", "伸个懒腰\n让身体透透气～"),
    Happy("happy", "开心表情陪伴", "开心传给你", "今天的小美好\n一起收藏～"),
    Worried("worried", "担忧表情陪伴", "我在这里呢", "慢慢来就好\n我一直陪着你～");

    companion object {
        fun fromBackendKey(key: String): HomeReminderKind? = entries.firstOrNull { it.backendKey == key }
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
    if (event == null) {
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
        Box(Modifier.offset(x = 100.52.dp * s, y = 257.dp * verticalScale)
            .size(352.48.dp * s, 248.dp * s).clipToBounds().testTag("buddy_reminder_${event.kind.backendKey}")) {
            ReminderArtwork(event, controller.finished, s) { controller.finish(event.id) }
        }
    }
}

/** Transparent source bounds are calibrated per asset, rather than stretching the entire WebP into the IP slot. */
@Composable
private fun ReminderArtwork(event: HomeReminderEvent, completed: Boolean, s: Float, onFinished: () -> Unit) {
    val progress = remember(event.id) { Animatable(if (completed) 1f else 0f) }
    val inspection = LocalInspectionMode.current || !LocalBuddyMotionEnabled.current
    val temporaryMotion = event.kind !in listOf(HomeReminderKind.Drink, HomeReminderKind.Fatigue)
    LaunchedEffect(event.id) {
        if (temporaryMotion && !completed && !inspection) { progress.animateTo(1f, tween(1600)); if (event.kind in listOf(HomeReminderKind.Happy, HomeReminderKind.Worried)) onFinished() }
    }
    val pose = Modifier.graphicsLayer {
        val pulse = if (completed || inspection) 0f else sin(progress.value * Math.PI * 2).toFloat()
        transformOrigin = TransformOrigin(.5f, 1f)
        if (event.kind == HomeReminderKind.Posture) rotationZ = pulse * 3f
        if (event.kind == HomeReminderKind.Stand || event.kind == HomeReminderKind.Happy) translationY = -kotlin.math.abs(pulse) * 6.dp.toPx() * s
        if (event.kind == HomeReminderKind.Worried) rotationZ = pulse * 2f
    }
    when (event.kind) {
        HomeReminderKind.Drink -> {
            // The original 1120×640 WebP has visible pixels x306..814/y2..552.
            // This fits the Figma visible character around x177..355/y259..456.
            BuddyWebpOnce(BuddyClip.Drink, description = "LookBuddy 饮水提醒", completed = completed,
                modifier = Modifier.offset(x = (-30.75).dp * s, y = 2.54.dp * s)
                    .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(392.44.dp * s, 224.25.dp * s),
                contentScale = ContentScale.Fit, onFinished = onFinished)
        }
        HomeReminderKind.Fatigue -> BuddyWebpOnce(BuddyClip.Fatigue, description = "LookBuddy 疲劳提醒",
            modifier = Modifier.offset(x = 41.6.dp * s, y = 48.1.dp * s)
                .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(247.96.dp * s, 141.69.dp * s),
            completed = completed, contentScale = ContentScale.Fit, onFinished = onFinished)
        HomeReminderKind.Posture, HomeReminderKind.Stand -> BuddyWebpOnce(BuddyClip.Introduction,
            description = "LookBuddy ${event.kind.label}",
            modifier = Modifier.offset(x = (-14.1).dp * s, y = (-7.1).dp * s)
                .wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(357.3.dp * s, 204.2.dp * s).then(pose),
            completed = completed, contentScale = ContentScale.Fit, onFinished = onFinished)
        HomeReminderKind.Happy, HomeReminderKind.Worried -> Image(
            painterResource(if (event.kind == HomeReminderKind.Happy) R.drawable.mood_happy else R.drawable.mood_sad),
            "LookBuddy ${event.kind.label}",
            Modifier.offset(x = 77.dp * s, y = 25.dp * s).size(178.dp * s).then(pose), contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun HomeReminderBubble(
    controller: HomeReminderController,
    hidden: Boolean,
    s: Float,
    verticalScale: Float,
    onAcknowledged: (String) -> Unit
) {
    val event = controller.active
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
            Box(Modifier.width(104.dp * s).heightIn(min = maxOf(44.dp, 44.dp * s))
                .clip(RoundedCornerShape(22.dp * s)).background(Color.White.copy(alpha = .48f))
                .clickable(enabled = event?.id == display.id && !hidden, role = Role.Button) {
                    controller.acknowledge()?.let(onAcknowledged)
                }.testTag("acknowledge_reminder"), contentAlignment = Alignment.Center) {
                Text("知道了", color = CompanionInk, fontSize = (15f * s).sp, lineHeight = (22f * s).sp)
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
