package com.lookbuddy.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prevent animated files disguised as PNG from crashing Compose painterResource on devices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StaticArtworkResourceTest {
    @Test fun buddyArtworkContainsARealPng() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val header = resources.openRawResource(R.drawable.diary_buddy).use { input ->
            ByteArray(8).also { bytes ->
                check(input.read(bytes) == bytes.size)
            }
        }
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            header
        )
    }
}