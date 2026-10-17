@file:OptIn(com.anker.esiotkit.support.InternalIotApi::class)
package com.lookbuddy.app

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.anker.esiotkit.media.VideoInfo
import com.anker.esiotkit.media.event_stream.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun DiaryEntryMedia(entry: JSONObject, candidates: JSONArray? = null) {
    val client = LocalProduct.current ?: return
    val identity = client.diaryIdentity
    if (entry.optBoolean("hasUploadedImage") || entry.optString("imageDataUrl").isNotBlank()) {
        DiaryUploadedImage(entry, client, identity)
    }
    val scope = rememberCoroutineScope()
    val source = entry.optJSONArray("mediaItems") ?: JSONArray()
    var removed by remember(entry, identity) { mutableStateOf(setOf<String>()) }
    var selected by remember(entry, identity) { mutableStateOf<JSONObject?>(null) }
    var removing by remember(entry, identity) { mutableStateOf<JSONObject?>(null) }
    var linking by remember(entry, identity) { mutableStateOf(false) }
    var attached by remember(entry, identity) { mutableStateOf(listOf<JSONObject>()) }
    var error by remember(entry, identity) { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val items = (List(source.length()) { source.getJSONObject(it) } + attached).distinctBy { it.getString("id") }
        .filter { it.getString("id") !in removed }
    items.forEach { media ->
        key(media.getString("id")) {
            Row {
                TextButton(onClick = { selected = media }, modifier = Modifier.testTag("diary_media_${media.getString("id")}")) {
                    Text(if (media.optString("kind") == "video") "查看历史录像" else "查看设备图片")
                }
                TextButton(enabled = !working, onClick = { removing = media }) { Text("移除关联") }
            }
        }
    }
    if (entry.optString("category") == "主动记录") {
        TextButton(enabled = !working, onClick = { linking = true }) { Text("关联当天设备媒体") }
    }
    error?.let { Text(it, color = CompanionClay) }
    removing?.let { media ->
        AlertDialog(onDismissRequest = { if (!working) removing = null }, title = { Text("移除这项媒体关联？") },
            text = { Text("只从此条记录移除，不删除设备中的原始图片或录像。") },
            confirmButton = { TextButton(enabled = !working, onClick = {
                working = true; error = null
                scope.launch {
                    try {
                        client.diaryMedia.link(entry.getString("id"), media.getString("id"), false, identity)
                        removed = removed + media.getString("id"); removing = null
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "移除失败，请重试"; removing = null }
                    finally { working = false }
                }
            }) { Text("移除") } }, dismissButton = { TextButton(enabled = !working, onClick = { removing = null }) { Text("取消") } })
    }
    if (linking) {
        val choices = buildList {
            if (candidates != null) for (i in 0 until candidates.length()) {
                val record = candidates.getJSONObject(i)
                val assets = record.optJSONArray("mediaItems") ?: continue
                for (j in 0 until assets.length()) add(record.optString("time") to assets.getJSONObject(j))
            }
        }.distinctBy { it.second.getString("id") }.filter { choice -> items.none { it.getString("id") == choice.second.getString("id") } }
        AlertDialog(onDismissRequest = { if (!working) linking = false }, title = { Text("选择当天设备媒体") },
            text = { androidx.compose.foundation.lazy.LazyColumn {
                if (choices.isEmpty()) item { Text("没有可关联的设备媒体，请先同步当天设备记录。") }
                items(choices.size) { index ->
                    val (time, media) = choices[index]
                    TextButton(enabled = !working, onClick = {
                        working = true; error = null
                        scope.launch {
                            try {
                                client.diaryMedia.link(entry.getString("id"), media.getString("id"), true, identity)
                                attached = attached + media; removed = removed - media.getString("id"); linking = false
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "关联失败，请重试"; linking = false }
                            finally { working = false }
                        }
                    }) { Text("$time · ${if (media.optString("kind") == "video") "录像" else "图片"} ${index + 1}") }
                }
            } }, confirmButton = { TextButton(enabled = !working, onClick = { linking = false }) { Text("关闭") } })
    }
    selected?.let { media ->
        key(identity, media.getString("id")) {
            DiaryMediaDialog(media, client, identity) { selected = null }
        }
    }
}

@Composable
private fun DiaryUploadedImage(entry: JSONObject, client: ProductClient, identity: String) {
    var bitmap by remember(entry, identity) { mutableStateOf<Bitmap?>(null) }
    var error by remember(entry, identity) { mutableStateOf<String?>(null) }
    var attempt by remember(entry, identity) { mutableIntStateOf(0) }
    var expanded by remember(entry, identity) { mutableStateOf(false) }
    LaunchedEffect(entry, identity, attempt) {
        bitmap = null; error = null
        try { bitmap = client.diaryMedia.uploadedImage(entry, identity) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "已上传图片读取失败" }
    }
    bitmap?.let { image ->
        Image(image.asImageBitmap(), "已上传的工位日记图片", Modifier.fillMaxWidth().heightIn(max = 180.dp)
            .clickable { expanded = true }.testTag("diary_uploaded_image_${entry.optString("id")}"), contentScale = ContentScale.Fit)
        if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("已上传的工位图片") },
            text = { Image(image.asImageBitmap(), "工位图片预览", Modifier.fillMaxWidth(), contentScale = ContentScale.Fit) },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text("关闭") } })
    }
    if (bitmap == null && error == null) Text("正在读取已上传图片…", color = CompanionMuted)
    error?.let { Text(it, color = CompanionClay); TextButton(onClick = { attempt++ }) { Text("重试图片") } }
}

@Composable
private fun DiaryMediaDialog(media: JSONObject, client: ProductClient, identity: String, dismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var player by remember { mutableStateOf<EventMediaPlayer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    var active by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(identity, attempt, active) {
        bitmap = null; player = null
        if (!active) { bitmap = null; return@LaunchedEffect }
        error = null
        var ownedPlayer: EventMediaPlayer? = null
        try {
            if (media.getString("kind") == "image") bitmap = client.diaryMedia.image(media, identity)
            else { ownedPlayer = client.diaryMedia.player(media, identity); player = ownedPlayer }
            awaitCancellation()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "媒体读取失败，请重试" }
        finally {
            runCatching { ownedPlayer?.stopPlay() }; runCatching { ownedPlayer?.release() }
            if (player === ownedPlayer) player = null
            bitmap = null
        }
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (media.optString("kind") == "video") "设备历史录像" else "设备图片") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            bitmap?.let { Image(it.asImageBitmap(), "工位日记设备图片", Modifier.fillMaxWidth().aspectRatio(1.5f), contentScale = ContentScale.Fit) }
            player?.takeIf { active }?.let { video -> key(video) { DiaryVideoPlayer(video, { error = it }) } }
            if (bitmap == null && player == null && error == null) Text(if (active) "正在读取设备媒体…" else "已暂停")
            error?.let { Text(it, color = CompanionClay); TextButton(onClick = { bitmap = null; player = null; attempt++ }) { Text("重试") } }
            Text("临时读取设备已有媒体；不上传到 LookBuddy 后端。")
        } }, confirmButton = { TextButton(onClick = dismiss) { Text("关闭") } })
}

@Composable
private fun DiaryVideoPlayer(player: EventMediaPlayer, onError: (String) -> Unit) {
    val handler = remember { Handler(Looper.getMainLooper()) }
    var ready by remember(player) { mutableStateOf(false) }
    var started by remember(player) { mutableStateOf(false) }
    val report by rememberUpdatedState(onError)
    DisposableEffect(player) {
        var alive = true
        player.setPlayListener(object : EventPlayListener {
            override fun onPlayState(status: EventPlayerState, message: String) {
                handler.post {
                    if (alive) {
                        if (status == EventPlayerState.Playing || status == EventPlayerState.Finished) started = true
                        if (status.name in listOf("Failed", "Timeout")) report("录像读取失败或超时，请检查设备与网络后重试")
                    }
                }
            }
            override fun onMuteState(isMute: Boolean) = Unit
            override fun onProgress(progress: Float) { handler.post { if (alive) started = true } }
            override fun onDownloadState(status: EventDownloadState, progress: Float, message: String) = Unit
            override fun onVideoInfo(info: VideoInfo) = Unit
        })
        onDispose { alive = false }
    }
    AndroidView(factory = { context -> FrameLayout(context).apply {
        try { player.initVideoView(this); ready = true }
        catch (_: Exception) { report("当前设备的录像预览初始化失败，请重试") }
    } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
    LaunchedEffect(player, ready) {
        if (ready) {
            withFrameNanos { }
            try { player.setMute(true); player.startPlay() }
            catch (_: Exception) { report("历史录像播放初始化失败，请重试"); return@LaunchedEffect }
            delay(30_000)
            if (!started) { runCatching { player.stopPlay() }; report("历史录像读取超时，请重试") }
        }
    }
}
