package org.example.foodblob.ui

import org.junit.Assert.*
import org.junit.Test

class BlobAppearanceTest {
    @Test fun `material input is finite bounded and quantized to twenty steps`() {
        assertEquals(0f, BlobAppearance.normalize(Float.NaN), 0f)
        assertEquals(0f, BlobAppearance.normalize(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, BlobAppearance.normalize(-2f), 0f)
        assertEquals(1f, BlobAppearance.normalize(2f), 0f)
        assertEquals(.5f, BlobAppearance.normalize(.519f), .00001f)
        assertEquals(.55f, BlobAppearance.normalize(.53f), .00001f)
        assertEquals(21, (0..1000).map { BlobAppearance.normalize(it / 1000f) }.distinct().size)
    }

    @Test fun `touches follow the visible body with a minimum seed target`() {
        val outline = listOf(org.example.foodblob.domain.BlobPoint(90.0, 90.0),
            org.example.foodblob.domain.BlobPoint(110.0, 90.0),
            org.example.foodblob.domain.BlobPoint(110.0, 110.0),
            org.example.foodblob.domain.BlobPoint(90.0, 110.0))
        assertTrue(ConnectedBlobMotion.acceptsTouch(org.example.foodblob.domain.BlobPoint(120.0, 100.0), outline, 48.0))
        assertFalse(ConnectedBlobMotion.acceptsTouch(org.example.foodblob.domain.BlobPoint(180.0, 180.0), outline, 48.0))
        assertFalse(ConnectedBlobMotion.acceptsTouch(org.example.foodblob.domain.BlobPoint(0.0, 0.0), emptyList(), 48.0))
    }

    @Test fun `held poke compresses broadly preserves area and rests exactly`() {
        val rest = ConnectedBlobMotion.heldPose(0.0)
        assertEquals(1.0, rest.scaleX, 0.0)
        assertEquals(1.0, rest.scaleY, 0.0)
        val held = ConnectedBlobMotion.heldPose(.9)
        assertTrue(held.scaleX >= 1.04)
        assertEquals(1.0, held.scaleX * held.scaleY, .000001)
        assertTrue(ConnectedBlobMotion.bodyPose(listOf(250)).scaleX > 1.08)
    }
}
