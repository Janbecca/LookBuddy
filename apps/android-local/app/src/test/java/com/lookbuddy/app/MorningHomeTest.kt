package com.lookbuddy.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MorningHomeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun desktopHeartbeatStopsOutsideForegroundAndCompositionAndSendsEmptyMessage() {
        val requests = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val server = recordingDesktopServer(requests)
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val message = mutableStateOf<String?>("喝口水吧")
        val visible = mutableStateOf(true)
        val repository = DesktopPetSyncRepository(server.url("/").toString(), "test-only")
        try {
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner,
                    LocalDesktopPetSyncRepository provides repository) {
                    if (visible.value) DesktopPetSyncEffect("drink", true, message.value, "test:stable")
                }
            }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(5000) { requests.isNotEmpty() }
            compose.waitUntil(5000) {
                compose.mainClock.advanceTimeBy(100)
                requests.size >= 2
            }
            compose.runOnIdle { message.value = "" }
            compose.waitUntil(5000) { requests.lastOrNull()?.optString("message") == "" }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.CREATED }
            val stopped = requests.size
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            assertEquals(stopped, requests.size)
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.waitUntil(5000) { requests.size > stopped }
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle() // Commit the removal before measuring post-disposal traffic.
            val removed = requests.size
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            assertEquals(removed, requests.size)
            assertTrue(requests.all { it.getString("event_id") == "test:stable" })
        } finally {
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
            server.shutdown()
        }
    }

    private fun recordingDesktopServer(requests: java.util.concurrent.ConcurrentLinkedQueue<JSONObject>) =
        okhttp3.mockwebserver.MockWebServer().apply {
            dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): okhttp3.mockwebserver.MockResponse {
                    requests.add(JSONObject(request.body.readUtf8()))
                    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"desktop":{"connected":false,"revision":1,"mood":"calm"}}""")
                }
            }
            start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        }

    @Test fun homeSnapshotMatchesVisibleReminderAndPausesUnderCalendar() {
        val requests = java.util.concurrent.ConcurrentLinkedQueue<JSONObject>()
        val server = recordingDesktopServer(requests)
        val controller = HomeReminderController().apply { preview(HomeReminderKind.Drink) }
        val repository = DesktopPetSyncRepository(server.url("/").toString(), "test-only")
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDesktopPetSyncRepository provides repository, LocalBuddyMotionEnabled provides false) {
                    MorningHomeScreen(previewTime = java.time.LocalDateTime.of(2026, 10, 17, 10, 0), reminders = controller)
                }
            }
            compose.waitUntil(5000) { requests.isNotEmpty() }
            val event = requests.last().getString("event_id")
            assertEquals("drink", requests.last().getString("scene"))
            assertEquals(HomeReminderKind.Drink.message, requests.last().getString("message"))
            compose.runOnIdle { controller.finish(event) }
            compose.waitUntil(5000) { requests.last().getBoolean("completed") }
            compose.onNodeWithTag("stats_toggle").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.waitUntil(5000) { requests.last().getString("message") == "" }
            assertEquals(event, requests.last().getString("event_id"))
            compose.onNodeWithContentDescription("心情日历").performClick()
            compose.onNodeWithTag("mood_calendar").assertExists()
            val hidden = requests.size
            compose.mainClock.advanceTimeBy(5000)
            compose.waitForIdle()
            assertEquals(hidden, requests.size)
        } finally { server.shutdown() }
    }

    @Test fun desktopDefaultsAndReleaseTransportRemainDisabledForPlainHttp() {
        assertTrue(!DesktopPetSyncRepository("").configured)
        assertTrue(desktopPetOriginAllowed("http://10.0.2.2:5211", debug = true))
        assertTrue(desktopPetOriginAllowed("http://192.168.1.2:5211", debug = true))
        assertTrue(!desktopPetOriginAllowed("http://192.168.1.2:5211", debug = false))
        assertTrue(!desktopPetOriginAllowed("http://localhost:5211", debug = false))
        assertTrue(!desktopPetOriginAllowed("http://example.com:5211", debug = true))
        assertTrue(!desktopPetOriginAllowed("https://example.com/path", debug = true))
        assertTrue(!desktopPetOriginAllowed("https://user@example.com", debug = true))
    }

    @Test fun previewReminderSurvivesRestorationAndHidesBehindExpandedDrawer() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
                MorningHomeScreen(previewDate = java.time.LocalDate.of(2026, 10, 17))
            }
        }
        compose.onNodeWithTag("buddy_normal").assertExists()
        compose.onNodeWithTag("home_state_preview").performClick()
        compose.onNodeWithTag("preview_drink").performClick()
        compose.onNodeWithTag("home_preview_label").assertTextEquals("本地提醒预览")
        compose.onNodeWithTag("buddy_reminder_drink").assertExists()
        compose.onNodeWithTag("home_reminder_bubble").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("buddy_reminder_drink").assertExists()
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
        compose.onNodeWithTag("home_state_preview").performClick()
        compose.onNodeWithTag("preview_normal").performClick()
        compose.onNodeWithTag("buddy_normal").assertExists()
        compose.onNodeWithTag("home_preview_label").assertDoesNotExist()
        compose.onNodeWithTag("nav_camera").assertIsDisplayed()
    }

    @Test fun greetingsAutoAcknowledgeWithoutStartingACloudReminderSource() {
        val controller = HomeReminderController().apply { preview(HomeReminderKind.MorningGreeting) }
        val acknowledged = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
                MorningHomeContent(greetingScene = null, reminders = controller,
                    onReminderAcknowledged = { acknowledged.add(it) })
            }
        }
        compose.mainClock.advanceTimeBy(6100)
        compose.runOnIdle {
            assertNull(controller.active)
            assertEquals(1, acknowledged.size)
        }
    }

    @Test fun missingCloudValuesStayUnknownAndRealSummaryValuesRenderWithoutLocalFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = ProductClient(context, scope, object : ProductSessionVault {
            override fun read(): JSONObject? = null
            override fun save(value: JSONObject?) = Unit
        })
        // This is a server-response fixture, not persisted local product data.
        client.summary.put("drinkCount", 7).put("stretchCount", 0).put("companionDays", 12)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalProduct provides client, LocalBuddyMotionEnabled provides false) {
                    MorningHomeContent(greetingScene = null)
                }
            }
            compose.onNodeWithTag("home_work_duration").assertTextEquals("--")
            compose.onNodeWithText("7 次").assertIsDisplayed()
            compose.onNodeWithText("0 次").assertIsDisplayed()
            compose.onNodeWithTag("home_companion_quote").assertTextContains("第12天", substring = true)
            compose.onNodeWithTag("offwork_mode").assertIsSelected()
            compose.onNodeWithContentDescription("开始实际陪伴").assertIsEnabled()
            compose.onNodeWithContentDescription("结束实际陪伴").assertIsNotEnabled()
        } finally { client.release(); scope.cancel() }
    }

    @Test fun measurementsAndSessionClockDoNotInventMissingValues() {
        assertNull(JSONObject().observedLong("drinkCount"))
        assertNull(JSONObject().put("drinkCount", JSONObject.NULL).observedLong("drinkCount"))
        assertEquals(0L, JSONObject().put("drinkCount", 0).observedLong("drinkCount"))
        val now = java.time.LocalDateTime.of(2026, 10, 17, 10, 0)
        assertEquals(60L, sessionElapsedMinutes(JSONObject().put("startedAt", "2026-10-17T01:00:00Z"), now, "Asia/Shanghai"))
        assertEquals(60L, sessionElapsedMinutes(JSONObject().put("startedAt", "2026-10-17T01:00:00"), now, "Asia/Shanghai"))
        assertNull(sessionElapsedMinutes(null, now, "Asia/Shanghai"))
    }

    @Test fun drawerExpandsShowsSupplementaryStatsAndCanCollapse() {
        compose.setContent { MorningHomeScreen() }
        compose.onNodeWithTag("collapsed_stats").assertExists()
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithText("本月工作时长").assertIsDisplayed()
        compose.onNodeWithText("今日坐姿").assertIsDisplayed()
        compose.onNodeWithText("今日小憩").assertIsDisplayed()
        compose.onNodeWithText("距周末还有").assertIsDisplayed()
        compose.onNodeWithText("收起").assertDoesNotExist()
        // Taps no longer dismiss an expanded panel.
        compose.onNodeWithTag("stats_toggle").performTouchInput { click() }
        compose.onNodeWithTag("nav_home").performClick()
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("stats_toggle").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("collapsed_stats").assertExists()
        compose.onNodeWithText("本月工作时长").assertDoesNotExist()
    }

    @Test fun drawerGestureAndSavedStateWork() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MorningHomeScreen() }
        compose.onNodeWithTag("collapsed_stats").performTouchInput { swipeUp() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("expanded_stats").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("expanded_stats").assertExists()
        compose.onNodeWithTag("expanded_stats").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("collapsed_stats").assertExists()
    }

    @Test fun drawerFollowsFingerBeforeReleaseAndShortSlowDragReturnsToClosed() {
        compose.setContent { MorningHomeScreen(previewDate = java.time.LocalDate.of(2026, 10, 13)) }
        val closedHeight = compose.onNodeWithTag("statistics_sheet").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("stats_toggle").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f))
            advanceEventTime(32)
        }
        val draggedHeight = compose.onNodeWithTag("statistics_sheet").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Panel should track the finger before release", draggedHeight > closedHeight + 30f)
        assertTrue("A partial drag must not jump straight to expanded", draggedHeight < closedHeight + 150f)
        compose.onNodeWithTag("stats_toggle").performTouchInput { advanceEventTime(400); up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("collapsed_stats").assertExists()
    }
}
