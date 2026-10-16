package com.lookbuddy.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun BuddyChatDesignScreenshot() = ChatScreenshot(design = true)

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun BuddyChatEmptyScreenshot() = ChatScreenshot()

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun BuddyChatConversationScreenshot() = ChatScreenshot(conversation = true)

@PreviewTest @Preview(widthDp=320, heightDp=640, fontScale=1.3f, showSystemUi=true, device="spec:width=320dp,height=640dp,dpi=160")
@Composable fun BuddyChatCompactScreenshot() = ChatScreenshot(conversation = true)

@PreviewTest @Preview(widthDp=393, heightDp=852, showSystemUi=true, device="spec:width=393dp,height=852dp,dpi=160")
@Composable fun DiaryChatEntryScreenshot() {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib, LocalBuddyMotionEnabled provides false) {
        CompanionPageLayout(CompanionPage.Diary) { s -> DiaryScreen(s) }
    }
}

@Composable private fun ChatScreenshot(design: Boolean = false, conversation: Boolean = false) {
    val state = remember { BuddyChatState().apply {
        if (conversation) { unsent.add("今天的事情有点多，想找你说说。先让我慢慢整理一下思绪。"); draft = "我想先从今天的会议说起" }
    } }
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib) {
        BuddyChatScreen(state, designPreview = design)
    }
}
