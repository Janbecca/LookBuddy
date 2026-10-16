package com.lookbuddy.app

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class OnboardingFlowTest {
    @get:Rule val compose = createComposeRule()
    private val preferences get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("lookbuddy_onboarding", Context.MODE_PRIVATE)

    @Before fun resetRegistration() { preferences.edit().clear().commit() }

    private fun launch() {
        compose.setContent { MaterialTheme { LookBuddyStartup { Text("已进入首页") } } }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
    }

    private fun advanceToNickname(reply: Int) {
        launch()
        for (page in listOf("Greeting", "Introduction", "Destiny", "ConnectionTeaser")) {
            compose.onNodeWithTag("page_$page").assertExists()
            compose.onNodeWithTag("reply_$reply").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(500)
        }
        compose.onNodeWithTag("page_Nickname").assertExists()
    }

    private fun completeConnection(index: Int) {
        compose.onNodeWithTag("page_DeviceConnection").assertExists()
        assertFalse(preferences.getBoolean("completed", false))
        compose.onNodeWithTag("connect_$index").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("page_FormalIntroduction").assertExists()
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
    }

    @Test fun arbitraryInputAndSecondaryRepliesCompleteRegistration() {
        advanceToNickname(1)
        compose.onNodeWithTag("nickname").performTextInput("随便起名 !")
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("phone").performTextInput("not-a-phone !")
        compose.onNodeWithTag("request_code").performScrollTo().performClick()
        compose.onNodeWithTag("code_feedback").assertExists()
        compose.onNodeWithTag("code").performTextInput("随便填 !")
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        completeConnection(5)
        compose.onNodeWithText("已进入首页").assertExists()
        assertTrue(preferences.getBoolean("completed", false))
    }

    @Test fun emptyInputAndNoCodeRequestCanStillContinue() {
        advanceToNickname(0)
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        completeConnection(0)
        compose.onNodeWithText("已进入首页").assertExists()
    }

    @Test fun registeredUserSkipsFirstRegistrationAfterSplash() {
        preferences.edit().putBoolean("completed", true).putInt("flow_version", 2).commit()
        launch()
        compose.onNodeWithText("已进入首页").assertExists()
        compose.onNodeWithTag("page_Greeting").assertDoesNotExist()
    }

    @Test fun keyboardDoneKeepsNicknameOnPageAndRestorationPreservesIt() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { LookBuddyStartup { Text("已进入首页") } } }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        repeat(4) {
            compose.onNodeWithTag("reply_0").performScrollTo().performClick()
            compose.mainClock.advanceTimeBy(500)
        }
        compose.onNodeWithTag("nickname").performTextInput("小禾")
        compose.onNodeWithTag("nickname").performImeAction()
        compose.onNodeWithTag("page_Nickname").assertExists()
        compose.onNodeWithTag("nickname").assertTextEquals("小禾")
        compose.onNodeWithTag("page_Phone").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("nickname").assertTextEquals("小禾")
        compose.onNodeWithTag("next").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("page_Phone").assertExists()
    }

    @Test fun oldRegistrationStillShowsTheNewOnboardingFlow() {
        preferences.edit().putBoolean("completed", true).commit()
        launch()
        compose.onNodeWithTag("page_Greeting").assertExists()
    }

    @Test fun splashIsVisibleBeforeFreshRegistrationStarts() {
        compose.mainClock.autoAdvance = false
        compose.setContent { MaterialTheme { LookBuddyStartup { Text("已进入首页") } } }
        compose.onNodeWithTag("page_Splash").assertExists()
        compose.mainClock.advanceTimeBy(2000)
        compose.onNodeWithTag("page_Splash").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("page_Greeting").assertExists()
    }

    @Test fun registeredUserCanReplayIntroductionAndCancelWithoutLosingData() {
        preferences.edit().putBoolean("completed", true).putInt("flow_version", 2).putString("nickname", "我的昵称").commit()
        compose.setContent {
            MaterialTheme {
                LookBuddyStartup {
                    val replay = LocalReplayIntroduction.current
                    Column {
                        Text("已进入首页")
                        TextButton(onClick = replay) { Text("重新体验初次见面") }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("重新体验初次见面").performClick()
        compose.onNodeWithTag("page_Splash").assertExists()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("page_Greeting").assertExists()
        compose.onNodeWithTag("exit_introduction").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("已进入首页").assertExists()
        assertTrue(preferences.getBoolean("completed", false))
        org.junit.Assert.assertEquals("我的昵称", preferences.getString("nickname", null))
    }

    @Test fun personalSettingsReplayEntryOpensTheFullStartupFlow() {
        preferences.edit().putBoolean("completed", true).putInt("flow_version", 2).commit()
        compose.setContent {
            MaterialTheme { LookBuddyStartup { LookBuddyApp(initialPage = CompanionPage.Settings) } }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("settings_settings_system").performClick()
        compose.onNodeWithTag("replay_introduction").performClick()
        compose.onNodeWithTag("page_Splash").assertExists()
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithTag("page_Greeting").assertExists()
    }
}
