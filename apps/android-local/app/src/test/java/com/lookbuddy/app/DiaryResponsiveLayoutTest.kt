package com.lookbuddy.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.lookbuddy.app.ui.theme.LookBuddyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiaryResponsiveLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Test fun loggedInViewportMatchesPreviewWithoutAnExtraHeader() {
        compose.setContent {
            ProductContentFrame(true, "后端在线", {}) { Box(Modifier.fillMaxSize().testTag("actual_content")) }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val content = compose.onNodeWithTag("actual_content").fetchSemanticsNode().boundsInRoot
        assertEquals(root.top, content.top, .1f)
        assertEquals(root.bottom, content.bottom, .1f)
        compose.onNodeWithTag("backend_setup").assertDoesNotExist()
    }

    @Test fun diaryAtDoubleFontKeepsBubblesApartAndInsideNarrowScreen() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f), LocalBuddyMotionEnabled provides false) {
                LookBuddyTheme { LookBuddyApp(initialPage = CompanionPage.Diary) }
            }
        }
        compose.onNodeWithTag("diary_adaptive_bubbles").assertExists()
        val bubbles = (0..2).map { compose.onNodeWithTag("diary_bubble_$it").fetchSemanticsNode().boundsInRoot }
        for (bubble in bubbles) assertTrue("Bubble fits screen width", bubble.left >= 0 && bubble.right <= 320f)
        assertTrue("First and second bubble do not overlap", bubbles[0].bottom <= bubbles[1].top)
        assertTrue("Second and third bubble do not overlap", bubbles[1].bottom <= bubbles[2].top)
        val chat = compose.onNodeWithTag("diary_chat_entry").fetchSemanticsNode().boundsInRoot
        assertTrue("Chat entry is separate from bubbles", chat.right <= bubbles[0].left)
        compose.onNodeWithText("记录的小事，会在这里慢慢积攒。").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val results = mutableListOf<TextLayoutResult>()
            read(results)
            val result = results.single()
            assertFalse("Diary text must wrap without overflow: size=${result.size}, lines=${result.lineCount}, width=${result.didOverflowWidth}, height=${result.didOverflowHeight}, constraints=${result.layoutInput.constraints}", result.hasVisualOverflow)
        }
        compose.onNodeWithTag("nav_camera").assertDoesNotExist()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        saveScreenshot("diary-320dp-font200.png")
    }

    @Test fun productTextAndActionsUseTheBundledFontAndDesignColor() {
        compose.setContent {
            LookBuddyTheme {
                assertEquals(CompanionClay, MaterialTheme.colorScheme.primary)
                Text("工位日记字体", modifier = Modifier.testTag("font_sample"))
            }
        }
        compose.onNodeWithTag("font_sample").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { read ->
            val results = mutableListOf<TextLayoutResult>()
            read(results)
            assertEquals(OnboardingDeviceFont, results.single().layoutInput.style.fontFamily)
        }
    }

    @Test
    @Config(sdk = [35], qualifiers = "w393dp-h852dp-mdpi")
    fun diaryAtDesignWidthKeepsTheOriginalCarouselAndThreeTabs() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1f), LocalBuddyMotionEnabled provides false) {
                LookBuddyTheme { ProductContentFrame(true, "后端在线", {}) { LookBuddyApp(initialPage = CompanionPage.Diary) } }
            }
        }
        compose.onNodeWithTag("diary_adaptive_bubbles").assertDoesNotExist()
        compose.onNodeWithTag("nav_camera").assertDoesNotExist()
        compose.onNodeWithTag("nav_diary").assertIsSelected()
        saveScreenshot("diary-393dp-font100.png")
    }

    private fun saveScreenshot(name: String) {
        val folder = System.getenv("LOOKBUDDY_LAYOUT_SCREENSHOT_DIR") ?: return
        val target = java.io.File(folder, name).apply { parentFile!!.mkdirs() }
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
