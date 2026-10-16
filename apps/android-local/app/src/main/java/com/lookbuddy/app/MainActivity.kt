package com.lookbuddy.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF344740)
private val Muted = Color(0xFF78877E)
private val CanvasBg = Color(0xFFF3F7F0)
private val Sage = Color(0xFFE5F0E2)
private val Leaf = Color(0xFF73906D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        setContent {
            MaterialTheme {
                LookBuddyStartup { LookBuddyApp() }
            }
        }
    }
}

@Composable
private fun LookBuddyHome() {
    var expanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    var stretchStarted by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasBg)
            .padding(horizontal = 7.dp)
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
        ) {
            Spacer(Modifier.height(27.dp))
            Header()
            Spacer(Modifier.height(21.dp))
            CompanionCard(stretchStarted) { stretchStarted = !stretchStarted }
            Spacer(Modifier.height(15.dp))
            RecentCard(expanded) { expanded = !expanded }
            Spacer(Modifier.height(15.dp))
            StatsCards()
            Spacer(Modifier.height(12.dp))
            MemoryRow()
            Spacer(Modifier.height(9.dp))
        }
        BottomNavigation(selectedTab) { selectedTab = it }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("周五，9 月 25 日 · 下午好，小禾", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text("和你一起，慢慢来。", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier.size(40.dp).background(Color(0xFFF8FAF7), CircleShape),
            contentAlignment = Alignment.Center
        ) { SimpleIcon("shield", Ink, Modifier.size(20.dp)) }
    }
}

@Composable
private fun CompanionCard(stretchStarted: Boolean, onStretchClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(270.dp)
            .background(Color(0xFFE8F3E4), RoundedCornerShape(30.dp))
    ) {
        Box(Modifier.size(74.dp).align(Alignment.CenterEnd).offset(x = (-19).dp, y = (-17).dp)
            .background(Color(0xFFF2F8EF), CircleShape))
        Box(Modifier.size(44.dp).align(Alignment.CenterStart).offset(x = 14.dp, y = 42.dp)
            .background(Color(0xFFF2F8EF), CircleShape))
        Column(Modifier.padding(start = 18.dp, top = 17.dp)) {
            Box(Modifier.background(Color.White, CircleShape).padding(horizontal = 16.dp, vertical = 5.dp)) {
                Text("● 陪伴中", color = Color(0xFF517259), fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("认真生活的你，\n今天也很可爱。", color = Ink, fontSize = 19.sp,
                lineHeight = 27.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(17.dp))
            Box(Modifier.background(Color(0xFFF5FAF2), CircleShape)
                .padding(horizontal = 18.dp, vertical = 6.dp)) {
                Text("不着急，慢慢来", color = Muted, fontSize = 10.sp)
            }
        }
        Text("Ⅱ", color = Color(0xFF52725C), fontSize = 17.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 17.dp, end = 22.dp))
        Mascot(Modifier.size(150.dp).align(Alignment.BottomCenter).offset(x = 36.dp, y = (-34).dp))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 19.dp, bottom = 24.dp)) {
            Text("连续在岗", color = Muted, fontSize = 11.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("52", color = Ink, fontSize = 29.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold)
                Text(" 分钟", color = Ink, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp)
                .background(Color.White, CircleShape).clickable(onClick = onStretchClick)
                .padding(horizontal = 30.dp, vertical = 9.dp)
        ) {
            Text(if (stretchStarted) "正在放松" else "起来松一松", color = Ink, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RecentCard(expanded: Boolean, onExpand: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(27.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近一小时", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", color = Muted, fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = onExpand))
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(top = 35.dp, start = 13.dp, end = 13.dp)
                .height(2.dp).background(Color(0xFFE4E8DA)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TimelineItem("专注", "14:24", 0)
                TimelineItem("喝水", "14:42", 1)
                TimelineItem("有点累", "15:06", 2)
                TimelineItem("舒展后", "15:24", 3)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Text("14:24 开始专注 · 14:42 喝水 · 15:06 稍作休息 · 15:24 舒展完成",
                color = Muted, fontSize = 10.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun TimelineItem(label: String, time: String, variant: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(63.dp)) {
        Mascot(Modifier.size(53.dp), variant)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Ink, fontSize = 11.sp)
        Text(time, color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun StatsCards() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCard("drop", "今日喝水", "3", "次", Color(0xFFE6F4FA), Modifier.weight(1f))
        StatCard("body", "姿态趋势", "舒展", "", Sage, Modifier.weight(1f))
        StatCard("moon", "疲劳线索", "2", "次", Color(0xFFF0ECFA), Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(icon: String, title: String, value: String, unit: String, tint: Color, modifier: Modifier) {
    Column(modifier.height(94.dp).background(tint, RoundedCornerShape(22.dp))
        .padding(start = 13.dp, top = 12.dp)) {
        SimpleIcon(icon, Ink, Modifier.size(16.dp))
        Spacer(Modifier.height(5.dp))
        Text(title, color = Muted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Ink, fontSize = if (value == "舒展") 20.sp else 23.sp,
                lineHeight = 26.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text("  $unit", color = Muted, fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun MemoryRow() {
    Row(
        Modifier.fillMaxWidth().height(35.dp).background(Color(0xFFFCFDFB), CircleShape)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SimpleIcon("spark", Ink, Modifier.size(16.dp))
        Text("帮我记住这一刻", color = Ink, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f))
        SimpleIcon("chevron", Ink, Modifier.size(14.dp))
    }
}

@Composable
private fun BottomNavigation(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("陪伴", "休息", "日记", "设备")
    val icons = listOf("home", "leaf", "book", "device")
    Column(Modifier.fillMaxWidth().padding(horizontal = 21.dp, vertical = 2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(58.dp)
                .background(Color.White, RoundedCornerShape(32.dp))
                .padding(horizontal = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            labels.forEachIndexed { index, label ->
                Column(
                    Modifier.width(72.dp).height(50.dp)
                        .background(if (selected == index) Sage else Color.Transparent, CircleShape)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    SimpleIcon(icons[index], if (selected == index) Color(0xFF5B8068) else Muted,
                        Modifier.size(20.dp))
                    Text(label, color = if (selected == index) Ink else Muted, fontSize = 10.sp,
                        fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Mascot(modifier: Modifier = Modifier, variant: Int = 0) {
    Canvas(modifier) {
        val sx = size.width / 150f
        val sy = size.height / 150f
        scale(sx, sy, pivot = Offset.Zero) {
            val body = when (variant) {
                1 -> Color(0xFFFFEBD8)
                2 -> Color(0xFFF7E8D1)
                3 -> Color(0xFFF5F5DD)
                else -> Color(0xFFFFFDEB)
            }
            drawOval(Color(0xFFDBE8D0), topLeft = Offset(35f, 128f), size = Size(92f, 16f))
            val leftArm = Path().apply {
                moveTo(31f, 92f); cubicTo(8f, 86f, 3f, 130f, 26f, 126f)
                cubicTo(43f, 123f, 46f, 97f, 31f, 92f)
            }
            drawPath(leftArm, body)
            val rightArm = Path().apply {
                moveTo(121f, 92f); cubicTo(144f, 86f, 150f, 129f, 130f, 126f)
                cubicTo(114f, 124f, 108f, 98f, 121f, 92f)
            }
            drawPath(rightArm, body)
            val head = Path().apply {
                moveTo(73f, 22f)
                cubicTo(35f, 20f, 22f, 43f, 21f, 86f)
                cubicTo(17f, 123f, 39f, 135f, 76f, 137f)
                cubicTo(113f, 136f, 131f, 121f, 128f, 85f)
                cubicTo(128f, 45f, 110f, 22f, 73f, 22f)
                close()
            }
            drawPath(head, body)
            val leaf1 = Path().apply {
                moveTo(76f, 25f); cubicTo(67f, 13f, 70f, 5f, 82f, 0f)
                cubicTo(89f, 14f, 84f, 22f, 76f, 25f); close()
            }
            val leaf2 = Path().apply {
                moveTo(76f, 25f); cubicTo(80f, 9f, 93f, 8f, 99f, 11f)
                cubicTo(98f, 22f, 88f, 26f, 76f, 25f); close()
            }
            drawPath(leaf1, Color(0xFF7C9D6E)); drawPath(leaf2, Color(0xFF9CB589))
            drawOval(Color(0xFFE9B3A2), Offset(30f, 92f), Size(20f, 10f))
            drawOval(Color(0xFFE9B3A2), Offset(102f, 92f), Size(20f, 10f))
            val eyeLeft = Path().apply {
                moveTo(48f, 86f); quadraticTo(53f, 78f, 58f, 86f)
            }
            val eyeRight = Path().apply {
                moveTo(95f, 86f); quadraticTo(100f, 78f, 105f, 86f)
            }
            val smile = Path().apply {
                moveTo(68f, 99f); quadraticTo(76f, 110f, 85f, 99f)
            }
            drawPath(eyeLeft, Ink, style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(eyeRight, Ink, style = Stroke(3f, cap = StrokeCap.Round))
            drawPath(smile, Ink, style = Stroke(3f, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun SimpleIcon(kind: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = (w / 13f).coerceAtLeast(1.5f)
        when (kind) {
            "shield" -> {
                val p = Path().apply {
                    moveTo(w * .5f, h * .08f); lineTo(w * .83f, h * .22f)
                    lineTo(w * .79f, h * .62f); quadraticTo(w * .7f, h * .81f, w * .5f, h * .91f)
                    quadraticTo(w * .3f, h * .81f, w * .21f, h * .62f)
                    lineTo(w * .17f, h * .22f); close()
                }
                drawPath(p, color, style = Stroke(stroke))
                drawLine(color, Offset(w * .35f, h * .51f), Offset(w * .46f, h * .61f), stroke)
                drawLine(color, Offset(w * .46f, h * .61f), Offset(w * .68f, h * .38f), stroke)
            }
            "drop" -> {
                val p = Path().apply {
                    moveTo(w * .5f, h * .08f); cubicTo(w * .3f, h * .35f, w * .2f, h * .54f, w * .2f, h * .66f)
                    cubicTo(w * .2f, h * 1.04f, w * .8f, h * 1.04f, w * .8f, h * .66f)
                    cubicTo(w * .8f, h * .54f, w * .7f, h * .35f, w * .5f, h * .08f)
                }
                drawPath(p, color, style = Stroke(stroke))
            }
            "moon" -> {
                drawCircle(color, w * .36f, Offset(w * .5f, h * .5f), style = Stroke(stroke))
                drawCircle(Color(0xFFF0ECFA), w * .35f, Offset(w * .67f, h * .34f))
            }
            "body" -> {
                drawCircle(color, w * .1f, Offset(w * .5f, h * .16f), style = Stroke(stroke))
                drawLine(color, Offset(w * .5f, h * .28f), Offset(w * .5f, h * .7f), stroke)
                drawLine(color, Offset(w * .14f, h * .43f), Offset(w * .86f, h * .43f), stroke)
                drawLine(color, Offset(w * .5f, h * .7f), Offset(w * .23f, h * .96f), stroke)
                drawLine(color, Offset(w * .5f, h * .7f), Offset(w * .77f, h * .96f), stroke)
            }
            "home" -> {
                val p = Path().apply {
                    moveTo(w * .12f, h * .45f); lineTo(w * .5f, h * .1f); lineTo(w * .88f, h * .45f)
                    lineTo(w * .88f, h * .9f); lineTo(w * .61f, h * .9f); lineTo(w * .61f, h * .61f)
                    lineTo(w * .39f, h * .61f); lineTo(w * .39f, h * .9f); lineTo(w * .12f, h * .9f); close()
                }
                drawPath(p, color, style = Stroke(stroke))
            }
            "leaf" -> {
                val p = Path().apply {
                    moveTo(w * .23f, h * .72f); cubicTo(w * .02f, h * .18f, w * .6f, h * .08f, w * .88f, h * .12f)
                    cubicTo(w * .88f, h * .55f, w * .63f, h * .95f, w * .23f, h * .72f)
                }
                drawPath(p, color, style = Stroke(stroke))
                drawLine(color, Offset(w * .18f, h * .94f), Offset(w * .67f, h * .38f), stroke)
            }
            "book" -> {
                val p = Path().apply {
                    moveTo(w * .5f, h * .24f); quadraticTo(w * .28f, h * .12f, w * .1f, h * .2f)
                    lineTo(w * .1f, h * .82f); quadraticTo(w * .28f, h * .73f, w * .5f, h * .86f)
                    quadraticTo(w * .72f, h * .73f, w * .9f, h * .82f)
                    lineTo(w * .9f, h * .2f); quadraticTo(w * .72f, h * .12f, w * .5f, h * .24f)
                }
                drawPath(p, color, style = Stroke(stroke))
                drawLine(color, Offset(w * .5f, h * .24f), Offset(w * .5f, h * .85f), stroke)
            }
            "device" -> {
                drawRoundRect(color, Offset(w * .17f, h * .14f), Size(w * .66f, h * .57f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .15f), style = Stroke(stroke))
                drawCircle(color, w * .08f, Offset(w * .5f, h * .43f), style = Stroke(stroke))
                drawLine(color, Offset(w * .35f, h * .86f), Offset(w * .65f, h * .86f), stroke)
            }
            "spark" -> {
                val p = Path().apply {
                    moveTo(w * .5f, 0f); lineTo(w * .59f, h * .39f); lineTo(w, h * .5f)
                    lineTo(w * .59f, h * .61f); lineTo(w * .5f, h)
                    lineTo(w * .41f, h * .61f); lineTo(0f, h * .5f)
                    lineTo(w * .41f, h * .39f); close()
                }
                drawPath(p, color, style = Stroke(stroke))
            }
            "chevron" -> {
                drawLine(color, Offset(w * .35f, h * .2f), Offset(w * .7f, h * .5f), stroke)
                drawLine(color, Offset(w * .7f, h * .5f), Offset(w * .35f, h * .8f), stroke)
            }
        }
    }
}

@Preview(name = "首页-早上-wyr", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun LookBuddyPreview() {
    MaterialTheme { MorningHomeScreen() }
}
