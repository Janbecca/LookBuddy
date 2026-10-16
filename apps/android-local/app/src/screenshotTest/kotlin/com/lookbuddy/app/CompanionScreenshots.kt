package com.lookbuddy.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import java.time.LocalDateTime

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun DiaryScreenshot() = PageScreenshot(CompanionPage.Diary)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun PersonalSettingsScreenshot() = PageScreenshot(CompanionPage.Settings)

@PreviewTest
@Preview(widthDp = 393, heightDp = 852, showSystemUi = true)
@Composable
fun CameraDisconnectedScreenshot() = PageScreenshot(CompanionPage.Camera)

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.3f, showSystemUi = true)
@Composable
fun CompactDiaryScreenshot() = PageScreenshot(CompanionPage.Diary)

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.3f, showSystemUi = true)
@Composable
fun CompactCameraScreenshot() = PageScreenshot(CompanionPage.Camera)

@PreviewTest
@Preview(widthDp = 320, heightDp = 640, fontScale = 1.3f, showSystemUi = true)
@Composable
fun CompactSettingsScreenshot() = PageScreenshot(CompanionPage.Settings)

@Composable
private fun PageScreenshot(page: CompanionPage) {
    CompositionLocalProvider(LocalHomeSvgLoader provides ::loadSvgForLayoutlib,
        LocalAppNow provides { LocalDateTime.of(2026, 10, 16, 12, 30) }) {
        CompanionPageLayout(page) { s ->
            when (page) {
                CompanionPage.Diary -> DiaryScreen(s)
                CompanionPage.Settings -> PersonalSettingsScreen(s, onCamera = {})
                else -> CameraScreen(UnconnectedCameraFeed(), s, LocalDateTime.of(2026, 10, 13, 12, 30))
            }
        }
    }
}
