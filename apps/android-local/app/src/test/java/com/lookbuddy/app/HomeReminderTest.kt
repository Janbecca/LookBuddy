package com.lookbuddy.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeReminderTest {
    @get:Rule val compose = createComposeRule()

    @Test fun acknowledgementDeduplicatesOnlyTheSameEventAndOldCompletionCannotFinishNewEvent() {
        val state = HomeReminderController()
        val first = HomeReminderEvent("drink-1", HomeReminderKind.Drink)
        state.receive(first); state.finish(first.id)
        assertTrue(state.finished)
        assertEquals(first.id, state.acknowledge())
        state.receive(first); assertNull(state.active)
        state.receive(HomeReminderEvent("drink-2", HomeReminderKind.Drink))
        state.finish(first.id)
        assertFalse(state.finished)
        assertEquals("drink-2", state.active?.id)
    }

    @Test fun allSixTypesHaveIndependentPreviewIdsAndUnknownBackendTypesAreIgnored() {
        val state = HomeReminderController()
        for (kind in HomeReminderKind.entries) {
            state.preview(kind); val id = state.active!!.id
            assertEquals(kind, state.active!!.kind)
            state.acknowledge(); state.preview(kind)
            assertNotEquals(id, state.active!!.id)
        }
        assertNull(HomeReminderKind.fromBackendKey("unknown"))
        assertEquals(HomeReminderKind.Posture, HomeReminderKind.fromBackendKey("posture"))
    }

    @Test fun backendSourceUsesTheSameAcknowledgementAndDoesNotReopenItsLatestEvent() {
        val events = kotlinx.coroutines.flow.MutableStateFlow(HomeReminderEvent("backend-1", HomeReminderKind.Drink))
        val confirmed = mutableListOf<String>()
        val source = object : HomeReminderSource {
            override val reminders = events
            override suspend fun acknowledge(id: String) { confirmed.add(id) }
        }
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) { LookBuddyApp(reminderSource = source) } }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home_reminder_bubble").assertExists()
        compose.onNodeWithTag("acknowledge_reminder").performClick()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(listOf("backend-1"), confirmed)
        compose.runOnIdle { events.value = HomeReminderEvent("backend-1", HomeReminderKind.Posture) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
        compose.runOnIdle { events.value = HomeReminderEvent("backend-2", HomeReminderKind.Worried) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("buddy_reminder_worried").assertExists()
    }

    @Test fun savedControllerRetainsCompletedFrameAndAcknowledgedIds() {
        val restoration = StateRestorationTester(compose)
        lateinit var state: HomeReminderController
        restoration.setContent { state = rememberHomeReminderController(); androidx.compose.material3.Text(state.active?.id ?: "idle") }
        compose.runOnIdle { state.receive(HomeReminderEvent("saved-1", HomeReminderKind.Fatigue)); state.finish("saved-1") }
        restoration.emulateSavedInstanceStateRestore()
        assertEquals("saved-1", state.active?.id)
        assertTrue(state.finished)
        compose.runOnIdle { state.acknowledge() }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state.receive(HomeReminderEvent("saved-1", HomeReminderKind.Fatigue)) }
        assertNull(state.active)
    }

    @Test fun previewSelectsEveryReminderAndAcknowledgementReturnsToNormal() {
        val controller = HomeReminderController()
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
            MorningHomeScreen(previewTime = LocalDateTime.of(2026,10,16,12,0), reminders = controller)
        } }
        for (kind in HomeReminderKind.entries) {
            compose.onNodeWithTag("home_state_preview").performClick()
            compose.onNodeWithTag("preview_${kind.backendKey}").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("buddy_reminder_${kind.backendKey}").assertExists()
            compose.onNodeWithTag("home_reminder_bubble").assertExists()
            val bubble = compose.onNodeWithTag("home_reminder_bubble").fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithTag("acknowledge_reminder").fetchSemanticsNode().boundsInRoot
            assertTrue("Acknowledgement is contained inside the bubble", bubble.contains(button.topLeft) && bubble.contains(button.bottomRight))
            compose.onNodeWithTag("acknowledge_reminder").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
            compose.onNodeWithTag("buddy_normal").assertExists()
        }
    }

    @Test fun drawerHidesButDoesNotAcknowledgeReminderAndItNeverTimesOut() {
        val state = HomeReminderController().apply { preview(HomeReminderKind.Drink); finish(active!!.id) }
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
            MorningHomeScreen(previewTime = LocalDateTime.of(2026,10,16,12,0), reminders = state)
        } }
        compose.mainClock.advanceTimeBy(8000)
        compose.onNodeWithTag("home_reminder_bubble").assertExists()
        compose.onNodeWithTag("stats_toggle").performClick()
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
        assertNotNull(state.active)
        compose.onNodeWithTag("expanded_stats").performTouchInput { swipeDown() }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithTag("home_reminder_bubble").assertExists()
        assertTrue(state.finished)
    }

    @Test fun reminderAndCompletionSurviveTabsAndRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) { LookBuddyApp() } }
        compose.onNodeWithTag("home_state_preview").performClick()
        compose.onNodeWithTag("preview_drink").performClick()
        compose.onNodeWithTag("nav_diary").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("nav_home").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home_reminder_bubble").assertExists()
        compose.onNodeWithTag("acknowledge_reminder").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactLargeFontKeepsAcknowledgementInsideBubbleWithAccessibleHitTarget() {
        val state = HomeReminderController().apply { preview(HomeReminderKind.Drink) }
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false,
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f)) {
            MorningHomeScreen(previewTime = LocalDateTime.of(2026,10,16,12,0), reminders = state)
        } }
        compose.mainClock.advanceTimeBy(300)
        val bubble = compose.onNodeWithTag("home_reminder_bubble").fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag("acknowledge_reminder").fetchSemanticsNode().boundsInRoot
        assertTrue(button.height >= 44f)
        assertTrue(bubble.contains(button.center))
        compose.onNodeWithTag("acknowledge_reminder").assertIsDisplayed().performClick()
        assertNull(state.active)
    }
}
