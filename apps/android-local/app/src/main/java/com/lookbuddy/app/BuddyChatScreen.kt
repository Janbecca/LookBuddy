package com.lookbuddy.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.innerShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Account-scoped conversation; failed messages stay available for explicit retry. */
@Stable
internal class BuddyChatState {
    var draft by mutableStateOf("")
    val unsent = mutableStateListOf<String>()
    val messages = mutableStateListOf<Pair<String, String>>()
    var sending by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var opening by mutableStateOf("今天想从哪件事说起？")

    fun enter(message: String? = null) {
        if (unsent.isEmpty() && messages.isEmpty() && !message.isNullOrBlank()) opening = message
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        unsent.add(text)
        draft = ""
    }

    suspend fun sendPending(client: ProductClient) {
        if (sending || unsent.isEmpty()) return
        sending = true; error = null
        val text = unsent.first()
        val identity = client.diaryIdentity
        try {
            require(text.length <= 4000) { "单条消息请控制在4000字以内" }
            val history = JSONArray()
            // Keep complete user/assistant pairs; never send system roles from UI state.
            messages.takeLast(2).forEach { (question, answer) ->
                history.put(JSONObject().put("role", "user").put("content", question))
                history.put(JSONObject().put("role", "assistant").put("content", answer.take(4000)))
            }
            history.put(JSONObject().put("role", "user").put("content", text))
            val result = JSONObject(client.request("/v1/buddy/chat", "POST", JSONObject().put("messages", history), expectedIdentity = identity))
            check(result.optString("source") == "llm" && result.optString("message").isNotBlank()) { "模型未返回有效回复，请重试" }
            messages.add(text to result.getString("message")); unsent.removeAt(0)
        } catch (e: CancellationException) { throw e }
        catch (e: ProductHttpException) {
            error = when (e.status) {
                404 -> "当前后端尚未发布聊天接口，请更新后端 main 版本后重试"
                503 -> "后端未启用聊天模型，请检查线上模型配置并重新发布"
                502 -> "模型请求失败，请检查线上模型名称、权限与额度后重试"
                504 -> "模型回复超时，请重试"
                else -> "聊天请求失败（HTTP ${e.status}），请重试"
            }
        } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message else "聊天连接失败，请检查网络后重试" }
        finally { sending = false }
    }

    companion object {
        val Saver = listSaver<BuddyChatState, String>(
            save = { listOf(it.draft, it.opening, "v2", JSONArray().apply {
                it.messages.takeLast(4).forEach { (question, answer) -> put(JSONArray().put(question).put(answer)) }
            }.toString()) + it.unsent },
            restore = { values -> BuddyChatState().apply {
                draft = values[0]; opening = values[1]
                if (values.getOrNull(2) == "v2") {
                    val history = JSONArray(values[3])
                    for (i in 0 until history.length()) messages.add(history.getJSONArray(i).getString(0) to history.getJSONArray(i).getString(1))
                    unsent.addAll(values.drop(4))
                } else unsent.addAll(values.drop(2))
            } }
        )
    }
}

@Composable internal fun rememberBuddyChatState() = rememberSaveable(saver = BuddyChatState.Saver) { BuddyChatState() }

/** Figma 299:3996, adapted to native insets/IME and the app's shared four-tab navigation. */
@Composable
internal fun BuddyChatScreen(
    state: BuddyChatState = rememberBuddyChatState(),
    onPage: (CompanionPage) -> Unit = {},
    designPreview: Boolean = false
) {
    val client = LocalProduct.current
    val scope = rememberCoroutineScope()
    fun send() {
        if (state.sending || state.unsent.isNotEmpty()) return
        state.submit()
        if (client != null) scope.launch { state.sendPending(client) }
    }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val backgroundFocus = remember { FocusRequester() }
    var inputBounds by remember { mutableStateOf<Rect?>(null) }
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("buddy_chat_screen")
        .focusRequester(backgroundFocus).focusable()
        .onGloballyPositioned { screenOrigin = it.localToWindow(Offset.Zero) }
        .pointerInput(focusManager, keyboard) {
            // Observe outside presses without consuming them, so scrolling and navigation still work.
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val up = event.changes.firstOrNull { it.changedToUpIgnoreConsumed() }
                    val bounds = inputBounds
                    if (up != null && bounds != null && !bounds.contains(up.position + screenOrigin)) {
                        focusManager.clearFocus(force = true)
                        backgroundFocus.requestFocus()
                        keyboard?.hide()
                    }
                }
            }
        }) {
        val s = (maxWidth.value / 393f).coerceAtMost(1.35f)
        val nativeTop = with(LocalDensity.current) { WindowInsets.statusBars.getTop(this).toDp() }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Image(painterResource(R.drawable.chat_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().imePadding()) {
            Spacer(Modifier.height(maxOf(nativeTop, 62.dp * s)))
            Box(Modifier.padding(horizontal = 20.dp * s).fillMaxWidth().heightIn(min = 60.dp * s)
                .background(Color(0xFFFFF3E2).copy(alpha = .5f), RoundedCornerShape(12.dp * s))
                .border(1.dp, Color.White.copy(alpha = .7f), RoundedCornerShape(12.dp * s))
                .padding(horizontal = 12.dp * s, vertical = 12.dp * s), contentAlignment = Alignment.Center) {
                Text("无论什么话，都可以和我讲，我一直在", color = Color.Black,
                    style = TextStyle(fontFamily = OnboardingDeviceFont, fontSize = (16 * s).sp,
                        lineHeight = (24 * s).sp), modifier = Modifier.testTag("chat_banner"))
            }
            val list = rememberLazyListState()
            LaunchedEffect(state.unsent.size, state.messages.size, state.sending) {
                if (!designPreview) list.animateScrollToItem(state.messages.size + state.unsent.size)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"),
                contentPadding = PaddingValues(start = 20.dp * s, end = 20.dp * s, top = 23.dp * s, bottom = 16.dp * s),
                verticalArrangement = Arrangement.spacedBy(16.dp * s)) {
                if (designPreview) {
                    items(5) { index -> ChatBubble("", index % 2 == 0, s, designPreview = true) }
                } else {
                    item { ChatBubble(state.opening, true, s) }
                    itemsIndexed(state.messages) { _, (question, answer) ->
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp * s)) {
                            ChatBubble(question, false, s)
                            ChatBubble(answer, true, s)
                        }
                    }
                    itemsIndexed(state.unsent) { index, text ->
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("chat_unsent_$index")) {
                            ChatBubble(text, false, s)
                            Text(if (state.sending) "正在等待模型回复…" else "暂未发送", color = CompanionInk, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, end = 52.dp * s))
                            if (!state.sending && client != null) TextButton(onClick = { scope.launch { state.sendPending(client) } }) { Text("重试发送") }
                            if (!state.sending) TextButton(onClick = { state.draft = state.unsent.removeAt(index); state.error = null }) { Text("重新编辑") }
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 12.dp * s, topEnd = 12.dp * s))
                .padding(horizontal = 14.dp * s, vertical = 19.dp * s)) {
                BasicTextField(value = state.draft, onValueChange = { state.draft = it.take(4000) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp * s, max = 144.dp)
                        .onGloballyPositioned { inputBounds = it.boundsInWindow() }
                        .background(Color(0xFFFBF9F9), RoundedCornerShape(36.dp * s))
                        .innerShadow(RoundedCornerShape(36.dp * s), Shadow(radius = 10.dp * s,
                            color = Color.Black.copy(alpha = .25f), offset = DpOffset(0.dp, 2.dp * s)))
                        .testTag("chat_input").semantics { contentDescription = "和Buddy聊聊" },
                    textStyle = TextStyle(color = CompanionInk, fontFamily = OnboardingDeviceFont,
                        fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(CompanionClay), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    decorationBox = { input ->
                        Row(Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (state.draft.isEmpty()) Text("和Buddy聊聊...", color = Color(0xFFAAAAAA),
                                    fontFamily = OnboardingDeviceFont, fontSize = 16.sp,
                                    modifier = Modifier.padding(start = 30.dp * s))
                                input()
                            }
                            IconButton(onClick = { send() }, enabled = state.draft.isNotBlank() && !state.sending && state.unsent.isEmpty(),
                                modifier = Modifier.size(48.dp).testTag("chat_send")) {
                                Icon(painterResource(R.drawable.ic_chat_send), contentDescription = "发送消息",
                                    tint = if (state.draft.isNotBlank()) CompanionClay else CompanionMuted.copy(alpha = .4f),
                                    modifier = Modifier.size(24.dp))
                            }
                        }
                    })
                if (!designPreview) Text(state.error ?: if (client == null) "聊天还未连接，内容暂留在本页" else "回复来自在线模型；本页对话随当前账号保留", color = CompanionMuted,
                    fontSize = 11.sp, modifier = Modifier.padding(start = 14.dp, top = 8.dp).testTag("chat_connection_hint"))
            }
            if (!imeVisible) CompanionNavigation(CompanionPage.Home, s, onPage)
        }
    }
}

@Composable
private fun ChatBubble(text: String, buddy: Boolean, s: Float, designPreview: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp * s), verticalAlignment = Alignment.Top) {
        if (buddy) ChatAvatar(true, s)
        Box(Modifier.weight(1f).heightIn(min = (if (designPreview) 104 else 64).dp * s)
            .background(Color.White.copy(alpha = .1f), RoundedCornerShape(12.dp * s))
            .border(1.dp, Color.White, RoundedCornerShape(12.dp * s)).padding(14.dp * s)) {
            if (text.isNotEmpty()) Text(text, color = CompanionInk,
                fontFamily = OnboardingDeviceFont, fontSize = (16 * s).sp, lineHeight = (25 * s).sp)
        }
        if (!buddy) ChatAvatar(false, s)
    }
}

@Composable private fun ChatAvatar(buddy: Boolean, s: Float) {
    Image(painterResource(if (buddy) R.drawable.chat_buddy_avatar else R.drawable.chat_user_avatar),
        if (buddy) "Buddy头像" else "用户头像占位", modifier = Modifier.size(48.dp * s)
            .clip(CircleShape).border(1.dp, Color.White, CircleShape), contentScale = ContentScale.Crop)
}
