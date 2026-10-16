package com.lookbuddy.app

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device: Robolectric intentionally returns static BitmapDrawables for WebP. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 28)
class BuddyWebpDeviceTest {
    @Test fun nativeDecoderRecognizesBothSuppliedWebps() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        for (id in listOf(R.raw.buddy_intro, R.raw.buddy_work)) {
            val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(resources, id))
            assertTrue(drawable is AnimatedImageDrawable)
            (drawable as AnimatedImageDrawable).repeatCount = 0
            assertEquals(0, drawable.repeatCount)
        }
    }
}
