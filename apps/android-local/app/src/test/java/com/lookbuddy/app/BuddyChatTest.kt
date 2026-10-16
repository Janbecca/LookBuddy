package com.lookbuddy.app

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class BuddyChatTest {
    @get:Rule val compose = createComposeRule()

    @Test fun homeChatIsOnlyAvailableInsideMappedReminderAndBackReturnsHome() {
        var back: OnBackPressedDispatcher? = null
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalBuddyMotionEnabled provides false) { LookBuddyApp() }
        }
        compose.onNodeWithTag("home_chat_entry").assertDoesNotExist()
        compose.onNodeWithTag("home_state_preview").performClick()
        compose.onNodeWithTag("preview_fatigue").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("acknowledge_reminder").performClick()
        compose.onNodeWithTag("buddy_chat_screen").assertIsDisplayed()
        compose.onNodeWithTag("chat_input").assertIsDisplayed()
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("morning_home").assertExists()
    }

    @Test fun diaryEntryReturnsToDiaryAndRestoresDraftAcrossRecreation() {
        val restoration = StateRestorationTester(compose)
        var back: OnBackPressedDispatcher? = null
        restoration.setContent {
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            CompositionLocalProvider(LocalBuddyMotionEnabled provides false) {
                LookBuddyApp(initialPage = CompanionPage.Diary)
            }
        }
        compose.onNodeWithTag("diary_chat_entry").performClick()
        compose.onNodeWithTag("chat_input").performTextInput("今天有些话想说")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("chat_input").assertTextContains("今天有些话想说")
        compose.runOnIdle { back!!.onBackPressed() }
        compose.onNodeWithTag("diary_screen").assertExists()
        compose.onNodeWithTag("diary_chat_entry").performClick()
        compose.onNodeWithTag("chat_input").assertTextContains("今天有些话想说")
    }

    @Test fun chatDoesNotTruncateAndNeverFakesAReplyOrDelivery() {
        val state = BuddyChatState()
        compose.setContent { BuddyChatScreen(state) }
        val longMessage = "今天的会议有点烦，我想慢慢讲清楚。".repeat(12)
        compose.runOnIdle { state.draft = longMessage; state.submit() }
        assertEquals(listOf(longMessage), state.unsent.toList())
        compose.onNodeWithTag("chat_unsent_0").assertExists()
        compose.onNodeWithText("暂未发送").assertExists()
        compose.onNodeWithTag("chat_connection_hint").assertExists()
        compose.runOnIdle { state.draft = "  "; state.submit() }
        assertEquals(1, state.unsent.size)
    }

    @Test fun fatigueAndExpressionButtonsOpenChatAndDoNotReopenOnHomeReturn() {
        compose.setContent { CompositionLocalProvider(LocalBuddyMotionEnabled provides false) { LookBuddyApp() } }
        for (key in listOf("fatigue", "happy", "worried")) {
            compose.onNodeWithTag("home_state_preview").performClick()
            compose.onNodeWithTag("preview_$key").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("acknowledge_reminder").assertTextContains("聊一聊").performClick()
            compose.onNodeWithTag("buddy_chat_screen").assertExists()
            compose.onNodeWithTag("nav_home").performClick()
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("home_reminder_bubble").assertDoesNotExist()
        }
    }

    @Test fun nonChatReminderKeysAndLabelsMatchTheBackendContract() {
        assertEquals(HomeReminderKind.Stand, HomeReminderKind.fromBackendKey("sedentary"))
        assertEquals(HomeReminderKind.Happy, HomeReminderKind.fromBackendKey("joyful"))
        assertEquals(HomeReminderKind.Happy, HomeReminderKind.fromBackendKey("good"))
        for (key in listOf("possibly_stressed", "possibly_low", "possibly_angry")) {
            assertEquals(HomeReminderKind.Worried, HomeReminderKind.fromBackendKey(key))
        }
        for (kind in listOf(HomeReminderKind.Drink, HomeReminderKind.Stand, HomeReminderKind.Posture)) {
            assertEquals("好的", kind.buttonLabel)
            assertFalse(kind.opensChat)
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun compactChatKeepsInputAndNavigationReachable() {
        compose.setContent { BuddyChatScreen() }
        compose.onNodeWithTag("chat_input").assertIsDisplayed().performTextInput("想聊聊")
        compose.onNodeWithTag("chat_send").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav_diary").assertIsDisplayed()
        compose.onNodeWithText("暂未发送").assertExists()
    }

    @Test fun sendIconIsAlwaysVisibleAndClickMatchesImeSendWithoutDuplicates() {
        val state = BuddyChatState()
        compose.setContent { BuddyChatScreen(state) }
        compose.onNodeWithTag("chat_send").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("chat_input").performTextInput("  第一条消息  ")
        compose.onNodeWithContentDescription("发送消息").assertExists()
        compose.onNodeWithTag("chat_send").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("第一条消息"), state.unsent.toList()) }
        compose.onNodeWithTag("chat_send").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("chat_input").performTextInput("第二条消息")
        compose.onNodeWithTag("chat_input").performImeAction()
        compose.runOnIdle {
            assertEquals(listOf("第一条消息", "第二条消息"), state.unsent.toList())
            assertEquals("", state.draft)
        }
        compose.onAllNodesWithText("暂未发送").assertCountEquals(2)
    }

    @Test fun tappingBannerOrConversationDismissesKeyboardAndPreservesDraft() {
        val state = BuddyChatState()
        var hidden = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hidden++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { BuddyChatScreen(state) }
        }
        compose.onNodeWithTag("chat_input").performTextInput("没有发送的草稿")
        compose.onNodeWithTag("chat_input").assertIsFocused()
        compose.runOnIdle { hidden = 0 }
        compose.onNodeWithTag("chat_banner").performTouchInput { click() }
        compose.onNodeWithTag("chat_input").assertIsNotFocused().assertTextContains("没有发送的草稿")
        compose.runOnIdle { assertTrue(hidden > 0); hidden = 0 }
        compose.onNodeWithTag("chat_input").performTouchInput { click() }
        compose.onNodeWithTag("chat_input").assertIsFocused()
        compose.runOnIdle { assertEquals(0, hidden) }
        compose.onNodeWithText(state.opening).performTouchInput { click() }
        compose.onNodeWithTag("chat_input").assertIsNotFocused()
        compose.runOnIdle { assertTrue(hidden > 0); assertTrue(state.unsent.isEmpty()) }
    }
}
