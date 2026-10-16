package com.lookbuddy.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
internal fun CameraPlaceholder(modifier: Modifier) {
    Box(modifier) {
        Image(painterResource(R.drawable.camera_preview), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text("示意画面 · 摄像头待连接", fontSize = 12.sp, color = CompanionInk,
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                .background(CompanionPaper.copy(alpha = .94f), RoundedCornerShape(8.dp)).padding(8.dp))
    }
}

@Composable
internal fun CameraScreen(feed: CameraFeed, s: Float, previewTime: LocalDateTime? = null) {
    val product = LocalProduct.current
    val state by feed.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(feed, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> feed.start()
                Lifecycle.Event.ON_STOP -> feed.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) feed.start()
        onDispose { owner.lifecycle.removeObserver(observer); feed.stop() }
    }
    val time = rememberAppTime(previewTime)
    var saving by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = state.connection == CameraConnection.Live && state.canMark
    Column(Modifier.fillMaxSize().testTag("camera_screen").verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp * s).padding(top = 11.dp * s, bottom = 22.dp * s),
        verticalArrangement = Arrangement.spacedBy(14.dp * s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 26.dp * s), verticalAlignment = Alignment.CenterVertically) {
            Text("陪伴视角", color = CompanionInk, fontSize = (20 * s).sp, modifier = Modifier.weight(1f))
            Text("LookBuddy", color = CompanionMuted, fontSize = (13 * s).sp)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp * s))
            .background(Color.White).border(.6.dp, CompanionBorder, RoundedCornerShape(12.dp * s))) {
            Row(Modifier.fillMaxWidth().heightIn(min = 34.dp * s).padding(horizontal = 12.dp * s),
                verticalAlignment = Alignment.CenterVertically) {
                FigmaSvg("camera", s, folder = "pages")
                Spacer(Modifier.width(8.dp * s))
                Text("摄像头原视频", color = CompanionInk, fontSize = (13 * s).sp, modifier = Modifier.weight(1f))
                Text(when (state.connection) {
                    CameraConnection.Live -> "设备视角"
                    CameraConnection.Connecting -> "连接中"
                    CameraConnection.Error -> "连接异常"
                    else -> "待连接"
                }, color = CompanionMuted, fontSize = (11 * s).sp)
            }
            Box(Modifier.fillMaxWidth().height(218.dp * s).clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                .testTag("camera_preview")) { feed.Preview(Modifier.fillMaxSize()) }
        }
        if (state.connection == CameraConnection.Error) {
            Text(state.message ?: "连接中断，请重新连接。", color = CompanionClay, fontSize = (12 * s).sp)
            Text("重新连接", color = CompanionClay, modifier = Modifier.clickable(role = Role.Button) { feed.start() }
                .padding(8.dp).testTag("camera_retry"))
        }
        Row(Modifier.cameraCard(s).heightIn(min = 38.dp * s).padding(horizontal = 12.dp * s),
            verticalAlignment = Alignment.CenterVertically) {
            Text("当前时间", color = CompanionMuted, fontSize = (12 * s).sp, modifier = Modifier.weight(1f))
            Text(time.format(DateTimeFormatter.ofPattern("yyyy.MM.dd  HH:mm")), color = CompanionInk, fontSize = (15 * s).sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp * s)) {
            CameraStatusCard("posture", "当前姿态", state.posture.takeIf { state.connection == CameraConnection.Live },
                "保持舒服，也记得活动", s, Modifier.weight(1f))
            CameraStatusCard("state", "当前状态", state.activity.takeIf { state.connection == CameraConnection.Live },
                "每一份努力都被看见", s, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp * s)
            .shadow(4.dp, RoundedCornerShape(12.dp * s)).clip(RoundedCornerShape(12.dp * s))
            .background(CompanionClay).clickable(enabled = !saving, role = Role.Button) {
                if (product != null) {
                    saving = true
                    scope.launch {
                        try { feedback = product.mark() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { feedback = "标记保存失败，请重试。" }
                        finally { saving = false }
                    }
                } else if (!ready) feedback = "请先连接摄像头，并等待视频缓冲就绪。"
                else {
                    saving = true; feedback = null
                    scope.launch {
                        try {
                            feedback = when (val result = feed.markMoment(MomentRequest())) {
                                is MomentResult.Saved -> "这一刻已保存到本机。"
                                is MomentResult.Unavailable -> result.reason
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { feedback = "保存失败，请稍后重试。" }
                        finally { saving = false }
                    }
                }
            }.testTag("mark_moment").padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FigmaSvg("mark", s, folder = "pages")
            Spacer(Modifier.width(10.dp * s))
            Text(if (saving) "正在记录…" else "Mark 一刻", color = CompanionPaper, fontSize = (27 * s).sp)
        }
        feedback?.let { Text(it, color = CompanionClay, fontSize = (12 * s).sp, modifier = Modifier.testTag("camera_feedback")) }
        Column(Modifier.cameraCard(s).heightIn(min = 100.dp * s).padding(12.dp * s),
            verticalArrangement = Arrangement.spacedBy(8.dp * s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FigmaSvg("about_mark", s, folder = "pages")
                Spacer(Modifier.width(8.dp * s))
                Text("什么是 Mark 一刻？", color = CompanionInk, fontSize = (14 * s).sp)
            }
            Text("手动标记时间与备注，进入你的工位日记。\n本轮不录制点击前后的视频。", color = CompanionMuted,
                fontSize = (13 * s).sp, lineHeight = (21 * s).sp)
        }
    }
}

private fun Modifier.cameraCard(s: Float) = fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp * s))
    .border(.6.dp, CompanionBorder, RoundedCornerShape(12.dp * s))

@Composable
private fun CameraStatusCard(icon: String, label: String, value: String?, hint: String, s: Float, modifier: Modifier) {
    Column(modifier.cameraCard(s).heightIn(min = 128.dp * s).padding(12.dp * s),
        verticalArrangement = Arrangement.spacedBy(5.dp * s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FigmaSvg(icon, s, folder = "pages")
            Spacer(Modifier.width(8.dp * s))
            Text(label, color = CompanionInk, fontSize = (14 * s).sp)
        }
        Text(value ?: "待识别", color = CompanionClay, fontSize = (if (value == null) 26 else 30).times(s).sp,
            lineHeight = (40 * s).sp)
        Text(if (value == null) "连接摄像头后显示" else hint, color = CompanionMuted,
            fontSize = (12 * s).sp, lineHeight = (17 * s).sp)
    }
}
