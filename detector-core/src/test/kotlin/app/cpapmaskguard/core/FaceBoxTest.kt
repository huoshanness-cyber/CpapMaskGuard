package app.cpapmaskguard.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FaceBoxTest {
    @Test
    fun eyesFallInsideTheFaceAndLowerBandStartsBelowThem() {
        val boxes = FaceBox.fromEyes(
            eyeX = 240f,
            eyeY = 200f,
            eyeDistance = 80f,
            imageWidth = 480,
            imageHeight = 640,
        )
        assertTrue(boxes.fromEyes)
        assertTrue(boxes.full.contains(240, 200))
        assertTrue(boxes.lower.top > 200)
        assertTrue(boxes.lower.top >= boxes.full.top)
        assertEquals(boxes.full.bottom, boxes.lower.bottom)
        assertTrue(boxes.lower.top < boxes.lower.bottom)
    }

    @Test
    fun boxesStayInsideTheImageWhenTheFaceIsNearTheEdge() {
        val boxes = FaceBox.fromEyes(
            eyeX = 10f,
            eyeY = 10f,
            eyeDistance = 40f,
            imageWidth = 200,
            imageHeight = 200,
        )
        assertTrue(boxes.full.left >= 0)
        assertTrue(boxes.full.top >= 0)
        assertTrue(boxes.full.right <= 200)
        assertTrue(boxes.full.bottom <= 200)
        assertTrue(boxes.lower.left >= boxes.full.left)
        assertTrue(boxes.lower.right <= boxes.full.right)
    }

    @Test
    fun tinyEyeDistanceFallsBackToCenter() {
        val boxes = FaceBox.fromEyes(
            eyeX = 100f,
            eyeY = 100f,
            eyeDistance = 4f,
            imageWidth = 200,
            imageHeight = 300,
        )
        assertFalse(boxes.fromEyes)
        assertTrue(boxes.full.left > 0)
        assertTrue(boxes.full.right < 200)
    }
}
