package com.lookbuddy.app

import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class BuddyClip(val raw: Int, val first: Int, val last: Int) {
    Introduction(R.raw.buddy_intro, R.drawable.buddy_intro_first, R.drawable.buddy_intro_last),
    Work(R.raw.buddy_work, R.drawable.buddy_work_first, R.drawable.buddy_work_last),
    Drink(R.raw.buddy_drink, R.drawable.buddy_drink_first, R.drawable.buddy_drink_last),
    Fatigue(R.raw.buddy_fatigue, R.drawable.buddy_fatigue_first, R.drawable.buddy_fatigue_last)
}
internal const val WorkReplayIntervalMs = 60L * 60L * 1000L
internal val LocalWorkReplayInterval = staticCompositionLocalOf { WorkReplayIntervalMs }
internal val LocalBuddyMotionEnabled = staticCompositionLocalOf { true }

@Stable
internal class WorkBuddyPlayback {
    var generation by mutableLongStateOf(0L)
        private set
    var completed by mutableStateOf(false)
        private set
    fun replay() { generation++; completed = false }
    fun finish(run: Long) { if (run == generation) completed = true }
}
internal val LocalWorkPlayback = staticCompositionLocalOf<WorkBuddyPlayback?> { null }

/** Kept above tabs: leaving/re-entering Home does not replay an already completed run. */
@Composable
internal fun rememberWorkBuddyPlayback(): WorkBuddyPlayback {
    val playback = remember { WorkBuddyPlayback() }
    val owner = LocalLifecycleOwner.current
    val interval = LocalWorkReplayInterval.current
    LaunchedEffect(owner, interval) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            playback.replay()
            while (true) { delay(interval); playback.replay() }
        }
    }
    return playback
}

/** Native WebP decoder, never painterResource(animatedWebp). repeatCount=0 means one play.
 * https://developer.android.com/reference/android/graphics/drawable/AnimatedImageDrawable
 * The exact decoded WebP last frame is also bundled as PNG to guarantee a stable end state.
 */
@Composable
internal fun BuddyWebpOnce(
    clip: BuddyClip,
    modifier: Modifier = Modifier,
    description: String = "LookBuddy",
    run: Long = 0L,
    completed: Boolean = false,
    onFinished: () -> Unit = {},
    contentScale: ContentScale = ContentScale.Crop
) {
    if (LocalInspectionMode.current || !LocalBuddyMotionEnabled.current || Build.VERSION.SDK_INT < 28) {
        Image(painterResource(clip.last), description, modifier, contentScale = contentScale)
        return
    }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { ImageView(context).apply { scaleType = if (contentScale == ContentScale.Fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP } }
    var finished by remember(clip, run) { mutableStateOf(completed) }
    var drawable by remember(clip, run) { mutableStateOf<Drawable?>(null) }
    val finishCallback by rememberUpdatedState(onFinished)
    val showLast = completed || finished
    LaunchedEffect(clip, run) {
        if (!completed) {
            drawable = withContext(Dispatchers.IO) {
                runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, clip.raw)) }.getOrNull()
            }
            if (drawable !is AnimatedImageDrawable) { finished = true; finishCallback() }
        }
    }
    AndroidView(factory = { view }, modifier = modifier, update = {
        it.contentDescription = description
        val next = if (showLast) null else drawable
        if (next != null) { if (it.drawable !== next) it.setImageDrawable(next) }
        else it.setImageResource(if (showLast) clip.last else clip.first)
    })
    DisposableEffect(drawable, showLast, owner, run) {
        val animation = drawable as? AnimatedImageDrawable
        val callback = object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable?) {
                finished = true
                view.setImageResource(clip.last)
                finishCallback()
            }
        }
        fun start() {
            if (animation != null && !showLast) {
                animation.repeatCount = 0
                animation.registerAnimationCallback(callback)
                animation.start()
            }
        }
        fun stop() { animation?.unregisterAnimationCallback(callback); animation?.stop() }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            if (event == Lifecycle.Event.ON_STOP) stop()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}
