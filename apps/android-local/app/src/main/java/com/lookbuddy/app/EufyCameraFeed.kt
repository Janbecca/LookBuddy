@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)
package com.lookbuddy.app

import android.graphics.Bitmap
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.viewinterop.AndroidView
import com.anker.esiotkit.core.device.MegaDeviceManager
import com.anker.esiotkit.media.*
import com.anker.esiotkit.media.live_stream.*
import com.lookbuddy.app.sdk.EufyAccountConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Native preview verified against ESIotKit 2.2.7 AAR and organizer sample. No recording. */
internal class EufyCameraFeed(private val deviceSn: String) : CameraFeed {
    private val mutable = MutableStateFlow(CameraFeedState())
    override val state: StateFlow<CameraFeedState> = mutable
    private var container: FrameLayout? = null
    private var player: LiveMediaPlayer? = null
    private var requested = false
    private var generation = 0
    override fun start() { if (mutable.value.connection == CameraConnection.Error) stop(); requested = true; playIfReady() }
    private fun playIfReady() {
        val view = container ?: return
        if (!requested || player != null) return
        val device = MegaDeviceManager.getDeviceBy(deviceSn)
        if (device == null) { mutable.value = CameraFeedState(CameraConnection.Error, "设备已失效，请重新连接"); return }
        val epoch = ++generation
        try {
            mutable.value = CameraFeedState(CameraConnection.Connecting, "正在连接官方摄像头")
            val next = LiveMediaPlayer(device)
            player = next
            next.setPlayListener(object : LivePlayListener {
                override fun onPlayState(status: LivePlayerState, message: String) {
                    if (generation != epoch) return
                    if (status == LivePlayerState.Failed || status == LivePlayerState.Timeout)
                        mutable.value = CameraFeedState(CameraConnection.Error, "视频连接失败/超时，请检查设备后重试")
                    if (status == LivePlayerState.Stop) mutable.value = CameraFeedState()
                }
                override fun onResolution(width: Int, height: Int) {
                    if (generation == epoch && width > 0 && height > 0)
                        mutable.value = CameraFeedState(CameraConnection.Live, "官方设备画面", canMark = false)
                }
                override fun onSpeakState(status: TalkState, message: String) = Unit
                override fun onRecordState(status: RecordState, timeStamp: Long, message: String) = Unit
                override fun onMuteState(isMute: Boolean) = Unit
                override fun onCutImage(image: Bitmap) = Unit
                override fun onRate(rate: Int) = Unit
                override fun onVideoInfo(info: VideoInfo) = Unit
            })
            next.initVideoView(view)
            next.setMute(true)
            next.startPlay(LiveParams().apply { liveType = LiveType.Hotspot })
        } catch (_: Exception) {
            runCatching { player?.release() }; player = null
            mutable.value = CameraFeedState(CameraConnection.Error, "预览初始化失败，请重新连接设备")
        }
    }
    override fun stop() {
        requested = false; generation++
        runCatching { player?.stopPlay() }; runCatching { player?.release() }
        player = null; container?.removeAllViews(); mutable.value = CameraFeedState()
    }
    @Composable override fun Preview(modifier: Modifier) {
        DisposableEffect(this) { onDispose { stop(); container = null } }
        AndroidView(factory = { FrameLayout(it).also { view -> container = view; playIfReady() } },
            update = { container = it; playIfReady() }, modifier = modifier)
    }
    override suspend fun markMoment(request: MomentRequest): MomentResult = MomentResult.Unavailable("本轮只保存标记，不录制视频")
}

@Composable internal fun EufyConnectionForm() {
    val client = LocalProduct.current
    if (client == null) { Text("设备接入预览"); return }
    var app by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var appDomain by remember { mutableStateOf("") }
    var deviceDomain by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("US") }
    Column {
        Text(client.sdkStatus)
        Text("官方账号配置仅在当前进程内使用；与演示手机号账号独立。")
        OutlinedTextField(app, { app = it }, label = { Text("App name") })
        OutlinedTextField(id, { id = it }, label = { Text("Client ID") })
        OutlinedTextField(user, { user = it }, label = { Text("eufy User ID") })
        OutlinedTextField(token, { token = it }, label = { Text("Authorization（不保存）") }, visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(appDomain, { appDomain = it }, label = { Text("App Domain HTTPS") })
        OutlinedTextField(deviceDomain, { deviceDomain = it }, label = { Text("Device Domain HTTPS") })
        OutlinedTextField(preset, { preset = it }, label = { Text("Preset key") })
        TextButton(enabled = !client.busy, onClick = {
            client.connectCamera(EufyAccountConfig(app, id, user, token, appDomain, deviceDomain, preset))
            token = ""
        }) { Text("连接官方 SDK 并选择在线设备") }
    }
}
