package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageScaleTest {
    @Test
    fun sampleSizeKeepsTheLongSideNearTheLimit() {
        assertEquals(1, ImageScale.inSampleSize(1280, 720, 960))
        assertEquals(2, ImageScale.inSampleSize(1920, 1080, 960))
        assertEquals(4, ImageScale.inSampleSize(4000, 3000, 960))
        assertEquals(1, ImageScale.inSampleSize(640, 480, 960))
    }
}
