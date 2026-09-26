package org.example.foodblob.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.SkinId
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PaintMaterialRenderingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun opaquePaintAndHalfAndGlassMatchAcrossNativeAndFallbackLighting() {
        val level = mutableStateOf(0f)
        val fallback = mutableStateOf(false)
        val skin = mutableStateOf(SkinId.SKY_MEADOW)
        val backdrop = mutableStateOf<Color?>(null)
        val native = createJellyVolumeMaterial()
        val mesh = MeshJellyVolumeMaterial()
        compose.setContent {
            Canvas(Modifier.size(260.dp).testTag("paint-material")) {
                drawRect(backdrop.value ?: if (skin.value == SkinId.SHRINE) Color(0xFF0A1421) else Color(0xFFF4F0DC))
                val contour = List(64) { i ->
                    val angle = i * 2 * PI / 64
                    val radius = size.width * .39 * (1 + .04 * sin(angle * 5))
                    BlobPoint(center.x + cos(angle) * radius, center.y + sin(angle) * radius * .94)
                }
                val path = Path().apply {
                    moveTo(contour.first().x.toFloat(), contour.first().y.toFloat())
                    contour.drop(1).forEach { lineTo(it.x.toFloat(), it.y.toFloat()) }
                    close()
                }
                (if (fallback.value) mesh else native).draw(this, path, JellyFrame(
                    JellyVolumeGeometry.profile(contour), Color(0xFF21D170), emptyList(), emptyList(),
                    Offset.Zero, 0f, skin.value, level.value,
                ))
            }
        }
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "paint-material-captures").apply { mkdirs() }
        for (useMesh in listOf(false, true)) for (world in SkinId.entries) {
            var previousTransmission = -1
            for ((name, amount) in listOf("paint" to 0f, "half" to .5f, "glass" to 1f)) {
                compose.runOnIdle { fallback.value = useMesh; skin.value = world; level.value = amount; backdrop.value = null }
                val image = compose.onNodeWithTag("paint-material").captureToImage().asAndroidBitmap()
                val center = image.getPixel(image.width / 2, image.height / 2)
                if (amount == 0f) assertTrue("Paint must retain dense green pigment",
                    android.graphics.Color.green(center) > android.graphics.Color.red(center) + 45)
                File(directory, "${if (useMesh) "mesh" else "native"}-${world.storageId}-$name.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                image.recycle()
                compose.runOnIdle { backdrop.value = Color.Black }
                val black = compose.onNodeWithTag("paint-material").captureToImage().asAndroidBitmap()
                compose.runOnIdle { backdrop.value = Color.White }
                val white = compose.onNodeWithTag("paint-material").captureToImage().asAndroidBitmap()
                val x = black.width / 2
                val y = black.height / 2
                val transmission = android.graphics.Color.green(white.getPixel(x, y)) - android.graphics.Color.green(black.getPixel(x, y))
                assertTrue("Opaque paint must hide the actual backdrop", amount != 0f || transmission <= 2)
                assertTrue("Jelly must transmit the actual backdrop", amount != 1f || transmission in 45..200)
                assertTrue("Each finish must visibly change transmission", transmission > previousTransmission)
                previousTransmission = transmission
                black.recycle()
                white.recycle()
            }
        }
    }
}
