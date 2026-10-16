package com.lookbuddy.app

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal data class DiaryEntry(val time: String, val title: String, val description: String, val category: String, val cropY: Float)

/** Design examples are available only after explicitly selecting the example diary. */
internal val DiaryExampleEntries = listOf(
    DiaryEntry("08:05", "展示今日OOTD", "新的工作日，带着好心情出发。", "到岗", .109f),
    DiaryEntry("10:10", "端起了水杯", "回应了今天的第3次饮水提醒。", "饮水", .286f),
    DiaryEntry("11:05", "长久专注", "你认真工作，我安静陪着你。", "专注", .469f),
    DiaryEntry("13:57", "午饭回来了", "吃饱啦，下午也一起慢慢来。", "回到工位", .646f),
    DiaryEntry("16:00", "喝水时间", "又给自己补充了一点水分。", "饮水", .823f),
    DiaryEntry("17:23", "有点疲惫了", "停一停，和我一起伸展一下。", "伸展", .469f),
    DiaryEntry("17:55", "收拾东西", "把桌面整理好，准备收工。", "整理", .646f),
    DiaryEntry("18:05", "挥手下班", "今天辛苦了，明天见！", "下班", .823f)
)

@Composable
internal fun DiaryScreen(s: Float) {
    val today = rememberAppTime().toLocalDate()
    var dateText by rememberSaveable { mutableStateOf<String?>(null) }
    var example by rememberSaveable { mutableStateOf(false) }
    val date = dateText?.let { LocalDate.parse(it) } ?: today
    val showExample = example
    var detail by remember { mutableStateOf<DiaryEntry?>(null) }
    val context = LocalContext.current
    val scroll = rememberScrollState()
    LaunchedEffect(date) { scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().testTag("diary_screen").verticalScroll(scroll)
        .padding(horizontal = 20.dp * s).padding(top = 10.dp * s, bottom = 28.dp * s),
        verticalArrangement = Arrangement.spacedBy(16.dp * s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 34.dp * s), verticalAlignment = Alignment.CenterVertically) {
            Text("陪伴日记", color = CompanionInk, fontSize = (22 * s).sp, modifier = Modifier.weight(1f))
            Text(date.format(DateTimeFormatter.ofPattern("M月d日 · E", Locale.CHINA)),
                color = CompanionMuted, fontSize = (11 * s).sp)
        }
        BuddyDiaryCarousel(s, showExample)
        if (showExample) {
            Text("设计稿示例 · 非真实时间记录", color = CompanionMuted, fontSize = 12.sp)
            TextButton(onClick = { example = false; dateText = null }) { Text("返回今天", color = CompanionClay) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("工位日记", color = CompanionInk, fontSize = (20 * s).sp, lineHeight = (29 * s).sp)
                Spacer(Modifier.height(4.dp * s))
                Text("一段段小事，也值得被记住", color = CompanionMuted, fontSize = (11 * s).sp)
            }
            Box(Modifier.size(48.dp * s).clickable(role = Role.Button) {
                DatePickerDialog(context, { _, year, month, day ->
                    example = false; dateText = LocalDate.of(year, month + 1, day).toString()
                }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                    datePicker.maxDate = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1
                }.show()
            }.semantics { contentDescription = "选择日记日期" }.testTag("diary_date"), contentAlignment = Alignment.Center) {
                FigmaSvg("diary_calendar", s, folder = "pages")
            }
        }
        if (showExample) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp * s)) {
                DiaryExampleEntries.forEachIndexed { i, entry -> DiaryTimelineEntry(entry, i, s) { detail = entry } }
            }
            Text("示例日记 · 来自设计稿，后续接入真实记录", color = CompanionMuted, fontSize = (11 * s).sp)
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("这一天还没有记录", color = CompanionInk, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                Text("和 Buddy 一起，慢慢积攒日常的小事。", color = CompanionMuted, fontSize = 12.sp)
                TextButton(onClick = { example = true }) { Text("看看示例日记", color = CompanionClay) }
            }
        }
    }
    detail?.let { entry ->
        AlertDialog(onDismissRequest = { detail = null }, containerColor = CompanionPaper,
            title = { Text("${entry.time} · ${entry.title}", color = CompanionInk) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DiaryKeyframe(entry, Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)))
                Text(entry.description, color = CompanionMuted)
                Text("这是设计稿中的示例关键帧，尚未接入真实视频。", color = CompanionMuted, fontSize = 12.sp)
            } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("知道啦", color = CompanionClay) } })
    }
}

@Composable
private fun BuddyDiaryCarousel(s: Float, example: Boolean) {
    val pager = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val fs = LocalDensity.current.fontScale.coerceAtLeast(1f)
    val groups = if (!example) List(3) { listOf("今天，也很高兴见到你。", "我在这里陪着你。", "记录的小事，会在这里慢慢积攒。") } else listOf(
        listOf("08:05，新的一天，你带着好心情来啦。", "10:10，又给自己补充了一点水分。", "你认真工作的时候，我就安静陪着你。"),
        listOf("17:23，你有点累了。我们一起伸展了一下。", "18:05，今天也辛苦啦，明天见！", "工位也被你收拾好了，今天的小事我都记着。"),
        listOf("13:57，吃饱啦，下午一起慢慢来。", "16:00，今天也有好好喝水。", "每一段小小的日常，都值得被记住。")
    )
    Box(Modifier.fillMaxWidth().height(223.dp * s * fs)) {
        VerticalPager(pager, Modifier.fillMaxSize().testTag("diary_bubbles")) { page ->
            Box(Modifier.fillMaxSize()) {
                DiaryBubble(groups[page][0], 13f, s, Modifier.offset(42.dp * s, 5.dp * s * fs)
                    .width(279.dp * s).heightIn(min = 55.dp * s * fs))
                DiaryBubble(groups[page][1], 12f, s, Modifier.offset(113.dp * s, 74.dp * s * fs)
                    .width(216.dp * s).heightIn(min = 28.dp * s * fs), soft = true)
                DiaryBubble(groups[page][2], 12f, s, Modifier.offset(121.dp * s, 120.dp * s * fs)
                    .width(217.dp * s).heightIn(min = 57.dp * s * fs))
                Text("上下轻滑动气泡", color = CompanionMuted, fontSize = (10 * s).sp,
                    modifier = Modifier.offset(184.dp * s, 183.dp * s * fs))
            }
        }
        BuddyWebpOnce(BuddyClip.Introduction, Modifier.offset(x = (-13).dp * s, y = 74.dp * s * fs)
            .size(121.dp * s, 126.dp * s), "LookBuddy 欢迎你翻开日记")
        Column(Modifier.align(Alignment.TopEnd).padding(top = 59.dp * s * fs, end = 9.dp * s),
            verticalArrangement = Arrangement.spacedBy(5.dp * s)) {
            repeat(3) { index -> Box(Modifier.size(5.dp * s, (if (index == pager.currentPage) 12 else 5).dp * s)
                .background(if (index == pager.currentPage) CompanionClay else CompanionBorder, RoundedCornerShape(5.dp))) }
        }
    }
}

@Composable
private fun DiaryBubble(text: String, size: Float, s: Float, modifier: Modifier, soft: Boolean = false) {
    Box(modifier.shadow(6.dp, RoundedCornerShape(28.dp * s), ambientColor = Color(0x0F45301F), spotColor = Color(0x0F45301F))
        .background(if (soft) Color(0xFFFFF3E4) else Color.White, RoundedCornerShape(28.dp * s))
        .padding(horizontal = 14.dp * s, vertical = (if (soft) 5 else 8).dp * s), contentAlignment = Alignment.CenterStart) {
        Text(text, color = CompanionInk, fontSize = (size * s).sp, lineHeight = ((size + 7) * s).sp)
    }
}

@Composable
private fun DiaryTimelineEntry(entry: DiaryEntry, index: Int, s: Float, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 106.dp * s), horizontalArrangement = Arrangement.spacedBy(10.dp * s)) {
        Text(entry.time, color = CompanionMuted, fontSize = (11 * s).sp, lineHeight = (16 * s).sp,
            modifier = Modifier.width(45.dp * s))
        Box(Modifier.width(14.dp * s).height(106.dp * s)) { FigmaSvg("timeline", s, folder = "pages") }
        Row(Modifier.weight(1f).heightIn(min = 106.dp * s).clip(RoundedCornerShape(16.dp * s))
            .background(Color.White).border(.75.dp, CompanionBorder.copy(alpha = .55f), RoundedCornerShape(16.dp * s))
            .clickable(role = Role.Button, onClick = onClick).testTag("diary_entry_$index").padding(12.dp * s),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp * s)) {
            Column(Modifier.weight(1f).heightIn(min = 82.dp * s), verticalArrangement = Arrangement.spacedBy(4.dp * s)) {
                Text(entry.title, color = CompanionInk, fontSize = (14 * s).sp, lineHeight = (21 * s).sp)
                Text(entry.description, color = CompanionMuted, fontSize = (10 * s).sp, lineHeight = (15 * s).sp)
                Text(entry.category, color = CompanionClay, fontSize = (9 * s).sp, lineHeight = (14 * s).sp)
            }
            DiaryKeyframe(entry, Modifier.size(96.dp * s, 64.dp * s).clip(RoundedCornerShape(10.dp * s)))
        }
    }
}

/** Preserve the original Figma image and apply its normalized CROP transform at render time. */
@Composable
private fun DiaryKeyframe(entry: DiaryEntry, modifier: Modifier) {
    val image = ImageBitmap.imageResource(R.drawable.diary_source)
    Canvas(modifier.semantics { contentDescription = "${entry.title} · 示例关键帧" }) {
        drawImage(image, srcOffset = IntOffset((image.width * .684f).roundToInt(), (image.height * entry.cropY).roundToInt()),
            srcSize = IntSize((image.width * .29f).roundToInt(), (image.height * .135f).roundToInt()),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}
