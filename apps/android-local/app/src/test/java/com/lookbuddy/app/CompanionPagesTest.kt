package com.lookbuddy.app

import android.content.Context
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.json.JSONObject
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

    private fun diaryClient(server: okhttp3.mockwebserver.MockWebServer, scope: CoroutineScope): ProductClient {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("lookbuddy.product.connection", Context.MODE_PRIVATE).edit()
            .putString("endpoint", server.url("/").toString().trimEnd('/')).commit()
        return ProductClient(context, scope, object : ProductSessionVault {
            override fun read() = JSONObject().put("accessToken", "test-only").put("refreshToken", "test-only")
                .put("user", JSONObject().put("id", "diary-fixture").put("username", "diary-fixture"))
            override fun save(value: JSONObject?) = Unit
        })
    }

    @Test fun diaryCrudPreservesRealEntriesAndFailedSaveKeepsTheDraft() {
        val body = java.util.concurrent.atomic.AtomicReference("原始日记正文")
        val failSave = java.util.concurrent.atomic.AtomicBoolean(false)
        val deletes = java.util.concurrent.atomic.AtomicInteger(0)
        val server = okhttp3.mockwebserver.MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                val response = okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                when (request.method) {
                    "GET" -> return response.setBody(JSONObject().put("content", body.get())
                        .put("entries", org.json.JSONArray().put(JSONObject().put("time", "10:10").put("category", "主动记录")
                            .put("title", "真实工位记录").put("description", "来自后端事件").put("media", "not_recorded"))).toString())
                    "POST" -> body.set("生成的日记正文")
                    "PATCH" -> {
                        if (failSave.get()) return response.setResponseCode(503).setBody("{}");
                        body.set(JSONObject(request.body.readUtf8()).getString("content"))
                    }
                    "DELETE" -> { deletes.incrementAndGet(); body.set("") }
                }
                return response.setBody("{}")
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = diaryClient(server, scope)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalProduct provides client, LocalBuddyMotionEnabled provides false) { DiaryScreen(1f) }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("原始日记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diary_generate").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("生成的日记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { !client.busy }
            compose.onNodeWithTag("diary_edit").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("diary_edit").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("diary_editor").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("diary_editor").performTextReplacement("用户编辑版本")
            compose.onNodeWithTag("diary_save").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("diary_editor").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("diary_body").assertTextEquals("用户编辑版本")
            failSave.set(true)
            compose.waitUntil(5000) { !client.busy }
            compose.onNodeWithTag("diary_edit").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("diary_edit").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("diary_editor").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("diary_editor").performTextReplacement("保存失败的草稿")
            compose.onNodeWithTag("diary_save").performClick()
            compose.waitUntil(5000) {
                compose.onAllNodesWithTag("diary_edit_error").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("diary_editor").assertTextContains("保存失败的草稿")
            assertEquals("用户编辑版本", body.get())
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithTag("diary_delete").performScrollTo().performClick()
            compose.onNodeWithText("取消").performClick()
            assertEquals(0, deletes.get())
            compose.onNodeWithTag("diary_delete").performScrollTo().performClick()
            compose.onNodeWithTag("diary_confirm_delete").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("diary_confirm_delete").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("diary_body").assertDoesNotExist()
            compose.onNodeWithTag("diary_record_0").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("真实工位记录").assertIsDisplayed()
            compose.onNodeWithTag("diary_entry_0").assertDoesNotExist()
            assertEquals(1, deletes.get())
        } finally { client.release(); scope.cancel(); server.shutdown() }
    }

    @Test fun diaryReadFailureIsNotAnEmptyOrExampleDiaryAndCanRetry() {
        val server = okhttp3.mockwebserver.MockWebServer()
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                if (attempts.incrementAndGet() == 1) {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    return okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}")
                }
                return okhttp3.mockwebserver.MockResponse().setBody("""{"content":"","entries":[]}""")
            }
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = diaryClient(server, scope)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalProduct provides client, LocalBuddyMotionEnabled provides false) { DiaryScreen(1f) }
            }
            compose.onNodeWithTag("diary_loading").performScrollTo().assertIsDisplayed()
            release.countDown()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("diary_load_error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diary_empty").assertDoesNotExist()
            compose.onNodeWithTag("diary_entry_0").assertDoesNotExist()
            compose.onNodeWithTag("diary_refresh").performScrollTo().performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("diary_empty").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("diary_load_error").assertDoesNotExist()
            compose.onNodeWithText("看看示例日记").performScrollTo().performClick()
            compose.onNodeWithText("设计稿示例 · 非真实时间记录").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("diary_entry_0").assertExists()
            compose.onNodeWithTag("diary_generate").assertDoesNotExist()
        } finally { release.countDown(); client.release(); scope.cancel(); server.shutdown() }
    }

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
        // The disk write precedes the async editor callback; wait for its UI transition too.
        compose.waitUntil(5000) {
            compose.onAllNodesWithTag("settings_settings_profile").fetchSemanticsNodes().isNotEmpty()
        }
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
