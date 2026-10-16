package com.lookbuddy.app

import android.content.Context
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class Mood(val label: String, val asset: String) {
    Happy("开心", "happy"), Calm("平静", "calm"), Tired("有点累", "tired"), Sad("低落", "sad")
}

/** One record per local calendar date. Editing a mood never redraws the daily fortune. */
internal class MoodStore(context: Context) {
    private val preferences = context.getSharedPreferences("lookbuddy_moods", Context.MODE_PRIVATE)
    fun read(date: LocalDate): Mood? = preferences.getString(date.toString(), null)
        ?.let { value -> Mood.entries.firstOrNull { it.name == value } }
    fun week(date: LocalDate): Map<LocalDate, Mood> = (6L downTo 0L).mapNotNull { days ->
        val day = date.minusDays(days)
        read(day)?.let { day to it }
    }.toMap()
    fun save(date: LocalDate, mood: Mood): Boolean =
        preferences.edit().putString(date.toString(), mood.name).commit()
}

private val CalendarInk = Color(0xFF40362B)
private val CalendarMuted = Color(0xFF918270)
private val CalendarBorder = Color(0xFFD6C2A6)
private val Paper = Color(0xFFFFFDFA)

/** Existing home remains mounted underneath the calendar, preserving its drawer and work mode. */
@Composable
internal fun MorningHomeScreen(
    initialExpanded: Boolean = false,
    initialCalendarOpen: Boolean = false,
    previewDate: LocalDate? = null,
    previewRecords: Map<LocalDate, Mood>? = null,
    onCamera: () -> Unit = {},
    onDiary: () -> Unit = {},
    onSettings: () -> Unit = {},
    previewTime: java.time.LocalDateTime? = null
) {
    val context = LocalContext.current
    val store = remember(context) { MoodStore(context) }
    val now = rememberAppTime(previewTime ?: previewDate?.atTime(9, 0))
    val date = now.toLocalDate()
    var open by rememberSaveable { mutableStateOf(initialCalendarOpen) }
    var records by remember(date) { mutableStateOf(previewRecords ?: store.week(date)) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (open) Modifier.blur(2.dp).clearAndSetSemantics {} else Modifier)) {
            // previewDate 非空时为测试 / 预览模式，跳过 GLM 气泡请求避免网络干扰
            MorningHomeContent(initialExpanded, greetingScene = if (previewDate != null) null else "morning_seated",
                onCamera = onCamera, onDiary = onDiary, onSettings = onSettings, onCalendar = {
                records = previewRecords ?: store.week(date)
                open = true
            }, now = now)
        }
        if (open) key(date) {
            val recordDate = date
            MoodCalendar(recordDate, records, onDismiss = { open = false }, onSave = { mood ->
                val success = withContext(Dispatchers.IO) { runCatching { store.save(recordDate, mood) }.getOrDefault(false) }
                if (success) records = records + (recordDate to mood)
                success
            })
        }
    }
}

@Composable
internal fun MoodCalendar(
    date: LocalDate,
    records: Map<LocalDate, Mood>,
    onDismiss: () -> Unit,
    onSave: suspend (Mood) -> Boolean
) {
    var choosing by rememberSaveable(date.toString()) { mutableStateOf(records[date] == null) }
    var pending by remember { mutableStateOf<Mood?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    BackHandler(onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize().testTag("mood_calendar"), contentAlignment = Alignment.Center) {
        val s = minOf(maxWidth.value / 393f, 1.35f)
        val shape = RoundedCornerShape(20.dp * s)
        Box(Modifier.matchParentSize().background(Color(0xFF332B21).copy(alpha = .34f))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            .semantics { contentDescription = "关闭心情日历遮罩" }.testTag("calendar_scrim"))
        Box(Modifier.offset(y = (if (choosing) (-20) else 0).dp * s)
            .statusBarsPadding().navigationBarsPadding().padding(vertical = 12.dp)
            .width(337.dp * s).heightIn(max = maxHeight - 48.dp)
            .shadow(20.dp * s, shape, ambientColor = Color(0x293B291A), spotColor = Color(0x293B291A))
            .clip(shape).background(Paper).border(.75.dp, Color(0xA6E8DBC7), shape)
            .semantics { paneTitle = "心情日历" }
            .animateContentSize(tween(if (reducedMotion) 0 else 400))) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .heightIn(min = (if (choosing) 420 else 402).dp * s)
                .padding(horizontal = 24.dp * s, vertical = 22.dp * s),
                verticalArrangement = Arrangement.spacedBy(16.dp * s)) {
                CalendarDate(date, s)
                Column(Modifier.fillMaxWidth().heightIn(min = (if (choosing) 168 else 110).dp * s)
                    .graphicsLayer { rotationY = rotation.value; cameraDistance = 16 * density; this.alpha = alpha.value },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (choosing) {
                        Text("忙了一天，心里还好吗？", color = CalendarInk, fontSize = (17 * s).sp,
                            lineHeight = (25 * s).sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp * s))
                        Text("不管什么心情，都可以放在这里", color = CalendarMuted, fontSize = (12 * s).sp,
                            lineHeight = (18 * s).sp, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp * s))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Mood.entries.forEach { mood ->
                                val selected = pending == mood
                                Column(Modifier.size(53.dp * s, 72.dp * s)
                                    .graphicsLayer { scaleX = if (selected) 1.04f else 1f; scaleY = scaleX }
                                    .background(if (selected) Color(0xFFF7EDDE) else Color.Transparent, RoundedCornerShape(10.dp * s))
                                    .border(.6.dp, CalendarBorder, RoundedCornerShape(10.dp * s))
                                    .clickable(enabled = !busy, role = Role.RadioButton) {
                                        busy = true; pending = mood; error = false
                                        scope.launch {
                                            // Let the selected state settle before persisting and turning the card.
                                            if (!reducedMotion) delay(100)
                                            if (runCatching { onSave(mood) }.getOrDefault(false)) {
                                                if (reducedMotion) {
                                                    alpha.snapTo(0f); choosing = false
                                                    alpha.animateTo(1f, tween(160))
                                                } else {
                                                    rotation.animateTo(90f, tween(200))
                                                    choosing = false
                                                    rotation.snapTo(-90f)
                                                    rotation.animateTo(0f, tween(200))
                                                }
                                            } else error = true
                                            pending = null; busy = false
                                        }
                                    }.testTag("mood_${mood.name}"),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp * s, Alignment.CenterVertically)) {
                                    MoodIcon(mood, s)
                                    Text(mood.label, color = CalendarInk, fontSize = (11 * s).sp, lineHeight = (16 * s).sp)
                                }
                            }
                        }
                        if (error) Text("刚刚没记住，再轻点一次好吗？", color = Color(0xFFA85E3D),
                            fontSize = (10 * s).sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("轻轻记下了 · ${records[date]?.label.orEmpty()}", color = CalendarMuted,
                                fontSize = (10 * s).sp, modifier = Modifier.weight(1f).testTag("recorded_mood"))
                            Text("换个心情", color = CalendarMuted, fontSize = (10 * s).sp,
                                modifier = Modifier.clickable(enabled = !busy, role = Role.Button) { choosing = true }
                                    .padding(vertical = 4.dp * s).testTag("change_mood"))
                        }
                        Text("今日宜缓", color = CalendarInk, fontSize = (36 * s).sp,
                            lineHeight = (47 * s).sp, modifier = Modifier.testTag("daily_fortune").semantics { heading() })
                        Spacer(Modifier.height(4.dp * s))
                        Text("遇事则缓，事缓则圆", color = CalendarMuted, fontSize = (15 * s).sp,
                            lineHeight = (19 * s).sp)
                    }
                }
                Box(Modifier.fillMaxWidth().height(.75.dp).background(Color(0xB3E0D6C7)))
                CalendarWeek(date, records, s)
            }
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp * s, end = 4.dp * s)
                .size(40.dp * s).clickable(role = Role.Button, onClick = onDismiss)
                .semantics { contentDescription = "关闭心情日历" }.testTag("calendar_close"), contentAlignment = Alignment.Center) {
                FigmaSvg("close", s, folder = "companion")
            }
        }
    }
}

@Composable
private fun CalendarDate(date: LocalDate, s: Float) {
    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp * s), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp * s)) {
        Text(date.dayOfMonth.toString(), color = CalendarInk, fontSize = (44 * s).sp, lineHeight = (58 * s).sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp * s)) {
            Text("${date.year}年${date.monthValue}月", color = CalendarInk, fontSize = (13 * s).sp, lineHeight = (17 * s).sp)
            Text("${date.format(DateTimeFormatter.ofPattern("EEEE", Locale.SIMPLIFIED_CHINESE))} · 今日",
                color = CalendarMuted, fontSize = (11 * s).sp, lineHeight = (15 * s).sp)
        }
        Box(Modifier.padding(end = 30.dp * s).size(30.dp * s)
            .border(.75.dp, CalendarBorder.copy(alpha = .7f), RoundedCornerShape(8.dp * s)),
            contentAlignment = Alignment.Center) { FigmaSvg("sun", s, folder = "companion") }
    }
}

@Composable
internal fun MoodIcon(mood: Mood, s: Float) = FigmaSvg(mood.asset, s, folder = "companion")

@Composable
private fun CalendarWeek(date: LocalDate, records: Map<LocalDate, Mood>, s: Float) {
    val format = remember { DateTimeFormatter.ofPattern("MM.dd") }
    Column(Modifier.fillMaxWidth().testTag("calendar_week"), verticalArrangement = Arrangement.spacedBy(12.dp * s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 16.dp * s), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("这一周的心情", color = CalendarMuted, fontSize = (11 * s).sp)
            Text("${date.minusDays(6).format(format)} — ${date.format(format)}", color = CalendarMuted, fontSize = (10 * s).sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.5.dp * s)) {
            (6L downTo 0L).forEach { days ->
                val day = date.minusDays(days)
                val today = days == 0L
                val mood = records[day]
                Column(Modifier.weight(1f).heightIn(min = 73.dp * s)
                    .background(if (today) Color(0xFFF7EDDE) else Color.Transparent, RoundedCornerShape(10.dp * s))
                    .then(if (today) Modifier.border(.75.dp, Color(0x99C49661), RoundedCornerShape(10.dp * s)) else Modifier)
                    .padding(vertical = 5.dp * s).testTag("mood_day_$day")
                    .semantics { contentDescription = "$day，${mood?.label ?: "未记录"}" },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp * s)) {
                    Text(listOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1],
                        color = CalendarMuted, fontSize = (9 * s).sp, lineHeight = (12 * s).sp)
                    Text(day.dayOfMonth.toString().padStart(2, '0'), color = if (today) Color(0xFFA66E42) else CalendarInk,
                        fontSize = (11 * s).sp, lineHeight = (15 * s).sp)
                    if (mood != null) Box(Modifier.graphicsLayer { alpha = if (today) 1f else .45f }) {
                        MoodIcon(mood, s * 21f / 26f)
                    } else Text("·", color = CalendarMuted, fontSize = (18 * s).sp, lineHeight = (27 * s).sp)
                }
            }
        }
    }
}
