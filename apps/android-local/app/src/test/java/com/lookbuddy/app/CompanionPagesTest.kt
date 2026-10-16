package com.lookbuddy.app

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CompanionPagesTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsKeepHomeDrawerAndSelectedPageAfterRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { LookBuddyApp() }
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("nav_diary").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_settings_device").performClick()
        compose.onNodeWithText("查看陪伴视角").performClick()
        compose.onNodeWithTag("camera_screen").assertExists()
    }

    @Test fun homeCameraOpensAndDisconnectedMarkNeverSaves() {
        compose.setContent { LookBuddyApp() }
        compose.onNodeWithContentDescription("陪伴视角").performClick()
        compose.onNodeWithTag("mark_moment").performScrollTo().performClick()
        compose.onNodeWithTag("camera_feedback").performScrollTo().assertTextEquals("请先连接摄像头，并等待视频缓冲就绪。")
        compose.onAllNodesWithText("待识别").assertCountEquals(2)
    }

    @Test fun diaryScrollsToLastEntryAndOpensHonestPreview() {
        compose.setContent { LookBuddyApp(CompanionPage.Diary) }
        compose.onNodeWithText("看看示例日记").performScrollTo().performClick()
        compose.onNodeWithTag("diary_bubbles").performTouchInput { swipeUp() }
        compose.onNodeWithTag("diary_entry_7").performScrollTo().performClick()
        compose.onNodeWithText("18:05 · 挥手下班").assertIsDisplayed()
        compose.onNodeWithText("这是设计稿中的示例关键帧，尚未接入真实视频。").assertIsDisplayed()
        compose.onNodeWithText("知道啦").performClick()
        compose.onNodeWithTag("nav_settings").assertIsDisplayed()
    }

    @Test fun profileCancelDoesNotSaveButSavePersists() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("lookbuddy_onboarding", Context.MODE_PRIVATE)
        prefs.edit().putString("nickname", "原昵称").commit()
        compose.setContent { LookBuddyApp(CompanionPage.Settings) }
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").performTextReplacement("取消的昵称")
        compose.onNodeWithText("取消").performClick()
        assertEquals("原昵称", prefs.getString("nickname", null))
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").assertTextContains("原昵称")
        compose.onNodeWithTag("profile_nickname").performTextReplacement("新的搭子")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) {
            prefs.getString("nickname", null) == "新的搭子"
        }
        compose.waitForIdle()
        compose.onNodeWithTag("settings_settings_profile").performClick()
        compose.onNodeWithTag("profile_nickname").assertTextContains("新的搭子")
        compose.onNodeWithText("取消").performClick()
    }

    @Test fun cameraAdapterGetsPreAndPostRollAndStopsWhenLeaving() {
        val feed = TestCameraFeed()
        compose.setContent { LookBuddyApp(CompanionPage.Camera, feed) }
        compose.onNodeWithText("测试实时画面").assertIsDisplayed()
        compose.onNodeWithTag("mark_moment").performScrollTo().performClick()
        compose.onNodeWithText("这一刻已保存到本机。").performScrollTo().assertIsDisplayed()
        assertEquals(MomentRequest(5, 5), feed.request)
        assertTrue(feed.starts > 0)
        compose.onNodeWithTag("nav_settings").performClick()
        compose.runOnIdle { assertTrue(feed.stops > 0) }
    }

    @Test fun cameraFailureIsReportedAndCanRetry() {
        val feed = TestCameraFeed().apply { fail = true }
        compose.setContent { LookBuddyApp(CompanionPage.Camera, feed) }
        compose.onNodeWithTag("mark_moment").performScrollTo().performClick()
        compose.onNodeWithText("保存失败，请稍后重试。").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { feed.fail = false }
        compose.onNodeWithTag("mark_moment").performScrollTo().performClick()
        compose.onNodeWithText("这一刻已保存到本机。").performScrollTo().assertIsDisplayed()
    }
}

private class TestCameraFeed : CameraFeed {
    override val state = MutableStateFlow(CameraFeedState(CameraConnection.Live, posture = "坐姿-端正", activity = "专注", canMark = true))
    var request: MomentRequest? = null
    var starts = 0
    var stops = 0
    var fail = false
    override fun start() { starts++ }
    override fun stop() { stops++ }
    @Composable override fun Preview(modifier: Modifier) { Text("测试实时画面", modifier = modifier) }
    override suspend fun markMoment(request: MomentRequest): MomentResult {
        this.request = request
        if (fail) error("Camera disconnected")
        return MomentResult.Saved(Uri.parse("content://test/moment.mp4"))
    }
}
