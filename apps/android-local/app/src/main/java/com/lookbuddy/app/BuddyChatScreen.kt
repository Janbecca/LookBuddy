package com.lookbuddy.app

import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.sp

/** Local conversation UI state. User messages remain visibly unsent until a real service is wired. */
@Stable
internal class BuddyChatState {
    var draft by mutableStateOf("")
    val unsent = mutableStateListOf<String>()
    var opening by mutableStateOf("今天想从哪件事说起？")

    fun enter(message: String? = null) {
        if (unsent.isEmpty() && !message.isNullOrBlank()) opening = message
    }

    fun submit() {
        val text = draft.trim()
        if (text.isEmpty()) return
        unsent.add(text)
        draft = ""
    }

    companion object {
        val Saver = listSaver<BuddyChatState, String>(
            save = { listOf(it.draft, it.opening) + it.unsent },
            restore = { values -> BuddyChatState().apply {
                draft = values[0]; opening = values[1]; unsent.addAll(values.drop(2))
            } }
        )
    }
}

@Composable internal fun rememberBuddyChatState() = rememberSaveable(saver = BuddyChatState.Saver) { BuddyChatState() }

/** Figma 299:3996, adapted to native insets/IME and the app's shared three-tab navigation. */
@Composable
internal fun BuddyChatScreen(
    state: BuddyChatState = rememberBuddyChatState(),
    onPage: (CompanionPage) -> Unit = {},
    designPreview: Boolean = false
) {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("buddy_chat_screen")) {
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
            LaunchedEffect(state.unsent.size) {
                if (!designPreview && state.unsent.isNotEmpty()) list.animateScrollToItem(state.unsent.size)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat_messages"),
                contentPadding = PaddingValues(start = 20.dp * s, end = 20.dp * s, top = 23.dp * s, bottom = 16.dp * s),
                verticalArrangement = Arrangement.spacedBy(16.dp * s)) {
                if (designPreview) {
                    items(5) { index -> ChatBubble("", index % 2 == 0, s, designPreview = true) }
                } else {
                    item { ChatBubble(state.opening, true, s) }
                    itemsIndexed(state.unsent) { index, text ->
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("chat_unsent_$index")) {
                            ChatBubble(text, false, s)
                            Text("暂未发送", color = CompanionInk, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp, end = 52.dp * s))
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 12.dp * s, topEnd = 12.dp * s))
                .padding(horizontal = 14.dp * s, vertical = 19.dp * s)) {
                BasicTextField(value = state.draft, onValueChange = { state.draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp * s, max = 144.dp)
                        .background(Color(0xFFFBF9F9), RoundedCornerShape(36.dp * s))
                        .innerShadow(RoundedCornerShape(36.dp * s), Shadow(radius = 10.dp * s,
                            color = Color.Black.copy(alpha = .25f), offset = DpOffset(0.dp, 2.dp * s)))
                        .testTag("chat_input").semantics { contentDescription = "和Buddy聊聊" },
                    textStyle = TextStyle(color = CompanionInk, fontFamily = OnboardingDeviceFont,
                        fontSize = 16.sp, lineHeight = 24.sp),
                    cursorBrush = SolidColor(CompanionClay), maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { state.submit() }),
                    decorationBox = { input ->
                        Row(Modifier.padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                if (state.draft.isEmpty()) Text("和Buddy聊聊...", color = Color(0xFFAAAAAA),
                                    fontFamily = OnboardingDeviceFont, fontSize = 16.sp,
                                    modifier = Modifier.padding(start = 30.dp * s))
                                input()
                            }
                            if (state.draft.isNotBlank()) TextButton(onClick = { state.submit() },
                                modifier = Modifier.testTag("chat_send")) { Text("发送", color = CompanionClay) }
                        }
                    })
                if (!designPreview) Text("聊天还未连接，内容暂留在本页", color = CompanionMuted,
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
