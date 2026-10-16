package com.lookbuddy.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.security.MessageDigest
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ReminderArtworkMappingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allMappedRawAnimationsMatchTheUsersOriginalGitBlobs() {
        val cases = listOf(
            Triple(HomeReminderKind.Drink, BuddyClip.Drink, "4aab1a5a0591fea463d11bec03dea8281aec20dd"),
            Triple(HomeReminderKind.Stand, BuddyClip.Stretch, "047e1660ffee09214b7ddf6c127b17b34010fe12"),
            Triple(HomeReminderKind.Posture, BuddyClip.Posture, "208d5c0729b51ce3472018e1293e63816ed2c82b"),
            Triple(HomeReminderKind.Fatigue, BuddyClip.Fatigue, "a8c2e1c855d109216245c9383ce22210e1ab6c7a"),
            Triple(HomeReminderKind.Happy, BuddyClip.Happy, "d660a282dffe83d909d0730d5f6539e2a2b967b0"),
            Triple(HomeReminderKind.Worried, BuddyClip.Concern, "ae47eec5cc88b8fae53c410b54e1fd29a8ac2fbc"),
            Triple(HomeReminderKind.MorningGreeting, BuddyClip.Morning, "aab98e9134ab00e049f07f46e8053e970606f027")
        )
        val resources = RuntimeEnvironment.getApplication().resources
        for ((kind, clip, expected) in cases) {
            assertEquals(clip, reminderArtworkSpec(kind)!!.clip)
            val bytes = resources.openRawResource(clip.raw).use { it.readBytes() }
            val sha = MessageDigest.getInstance("SHA-1").apply {
                update("blob ${bytes.size}\u0000".toByteArray()); update(bytes)
            }.digest().joinToString("") { "%02x".format(it) }
            assertEquals("Original source for $kind", expected, sha)
        }
    }

    @Test fun everyActiveAnimationHasTheSameVisibleHeightAsTheExistingDrinkPose() {
        val state = HomeReminderController()
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
            MorningHomeScreen(previewTime = LocalDateTime.of(2026,10,16,12,0), reminders = state)
        } }
        for (kind in HomeReminderKind.entries.filter { reminderArtworkSpec(it) != null }) {
            compose.runOnIdle { state.preview(kind); state.finish(state.active!!.id) }
            compose.mainClock.advanceTimeBy(300)
            val height = compose.onNodeWithTag("reminder_visible_artwork").fetchSemanticsNode().boundsInRoot.height
            assertEquals(kind.name, ReminderArtworkHeight, height, 1f)
        }
    }

    @Test fun greetingsHaveNoButtonsAndCloseWithoutOpeningChat() {
        val state = HomeReminderController()
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
            MorningHomeScreen(previewTime = LocalDateTime.of(2026,10,16,12,0), reminders = state)
        } }
        for (kind in listOf(HomeReminderKind.MorningGreeting, HomeReminderKind.EveningGreeting)) {
            compose.runOnIdle { state.preview(kind) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("home_reminder_bubble").assertExists()
            compose.onNodeWithTag("acknowledge_reminder").assertDoesNotExist()
            compose.mainClock.advanceTimeBy(6500)
            assertNull(state.active)
        }
    }

    @Test fun diaryPreviewOpensDiaryAndHomeHasNoStandaloneChatEntry() {
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) { LookBuddyApp() } }
        compose.onNodeWithTag("home_chat_entry").assertDoesNotExist()
        compose.onNodeWithTag("home_state_preview").performClick()
        compose.onNodeWithTag("preview_diary_ready").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("acknowledge_reminder").assertTextContains("去看看").performClick()
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("nav_home").performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
    }
}
