package com.lookbuddy.app

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal enum class CameraConnection { Disconnected, Connecting, Live, Error }

internal data class CameraFeedState(
    val connection: CameraConnection = CameraConnection.Disconnected,
    val message: String? = null,
    val posture: String? = null,
    val activity: String? = null,
    val canMark: Boolean = false
)

/** The adapter must keep a five-second rolling buffer to support pre-roll. */
internal data class MomentRequest(val secondsBefore: Int = 5, val secondsAfter: Int = 5)
internal sealed interface MomentResult {
    data class Saved(val uri: Uri) : MomentResult
    data class Unavailable(val reason: String) : MomentResult
}

/**
 * Future USB / RTSP / vendor-SDK adapter contract.
 * start/stop are non-blocking and idempotent; release the preview on stop.
 * Preview owns its SurfaceView/TextureView/AndroidView and must obey modifier bounds.
 * Publish Live only after receiving frames; publish canMark only when buffering is ready.
 * markMoment saves the real clip before returning its durable URI, or reports failure.
 */
internal interface CameraFeed {
    val state: StateFlow<CameraFeedState>
    fun start()
    fun stop()
    @Composable fun Preview(modifier: Modifier)
    suspend fun markMoment(request: MomentRequest): MomentResult
}

/** No camera permissions or SDK are requested until a real adapter is installed. */
internal class UnconnectedCameraFeed : CameraFeed {
    override val state: StateFlow<CameraFeedState> = MutableStateFlow(CameraFeedState())
    override fun start() = Unit
    override fun stop() = Unit
    @Composable override fun Preview(modifier: Modifier) = CameraPlaceholder(modifier)
    override suspend fun markMoment(request: MomentRequest): MomentResult =
        MomentResult.Unavailable("请先连接摄像头，再记录这一刻。")
}
