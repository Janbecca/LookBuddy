package com.lookbuddy.app

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BuddyAnimationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bundledWebpsContainTheExpectedAnimationFrames() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val expected = mapOf(BuddyClip.Introduction to 45, BuddyClip.Work to 66, BuddyClip.Drink to 45, BuddyClip.Fatigue to 66, BuddyClip.Stretch to 52, BuddyClip.Posture to 80, BuddyClip.Happy to 45, BuddyClip.Concern to 66, BuddyClip.Morning to 45)
        for ((clip, frameCount) in expected) {
            val data = resources.openRawResource(clip.raw).use { it.readBytes() }
            assertEquals("RIFF", String(data.copyOfRange(0, 4)))
            assertEquals("WEBP", String(data.copyOfRange(8, 12)))
            var offset = 12; var frames = 0; var animated = false
            while (offset + 8 <= data.size) {
                val name = String(data.copyOfRange(offset, offset + 4))
                var size = 0
                for (i in 0..3) size = size or ((data[offset + 4 + i].toInt() and 255) shl (8 * i))
                if (name == "ANIM") animated = true
                if (name == "ANMF") frames++
                offset += 8 + size + (size and 1)
            }
            assertTrue(animated)
            assertEquals(frameCount, frames)
        }
    }
    @Test fun staleCompletionCannotEndTheNextHourlyRun() {
        val state = WorkBuddyPlayback()
        state.replay(); val first = state.generation
        state.finish(first); assertTrue(state.completed)
        state.replay(); assertFalse(state.completed)
        state.finish(first); assertFalse(state.completed)
        state.finish(state.generation); assertTrue(state.completed)
        assertEquals(3_600_000L, WorkReplayIntervalMs)
    }
    @Test fun hourlySchedulerStopsInBackgroundAndRestartsWhenOpened() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        lateinit var playback: WorkBuddyPlayback
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalWorkReplayInterval provides 1000L) {
                val state = rememberWorkBuddyPlayback()
                SideEffect { playback = state }
                Text(state.generation.toString())
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        val first = playback.generation
        compose.runOnIdle { playback.finish(first) }
        compose.mainClock.advanceTimeBy(1100)
        assertTrue(playback.generation > first)
        assertFalse(playback.completed)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        val stopped = playback.generation
        compose.mainClock.advanceTimeBy(3000)
        assertEquals(stopped, playback.generation)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        assertTrue(playback.generation > stopped)
    }
    @Test fun moodAssetsCoverSixChoicesAndKeepLegacyRecords() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(6, Mood.pickerOptions.size)
        assertFalse(Mood.pickerOptions.contains(Mood.Tired))
        for (mood in Mood.pickerOptions) {
            val header = context.resources.openRawResource(mood.image).use { it.readBytes().take(8).toByteArray() }
            assertArrayEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a), header)
        }
        val date = java.time.LocalDate.of(2026,10,15)
        val store = MoodStore(context)
        assertTrue(store.save(date, Mood.Tired))
        assertEquals(Mood.Tired, store.read(date))
        assertEquals("有点累", store.read(date)?.label)
    }
}
