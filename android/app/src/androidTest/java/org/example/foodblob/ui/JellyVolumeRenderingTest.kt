package org.example.foodblob.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
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
import androidx.test.core.app.ApplicationProvider
import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.SkinId
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sqrt
import org.junit.Rule
import org.junit.Test

/** Hardware Canvas is required: JVM tests cannot compile or execute Android's AGSL program. */
class JellyVolumeRenderingTest {
    @get:Rule val compose = createComposeRule()

    /** Isolated CPU preparation timing, not a claim about device frame rate. */
    @Test fun captureAnalyticLightingPerformance() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureJellyPerformance") == "true")
        val contour = List(64) { i ->
            val angle = i*2*PI/64
            val radius = 1+.13*sin(angle*3)
            BlobPoint(cos(angle)*radius,sin(angle)*radius*.85)
        }
        val profile = JellyVolumeGeometry.profile(contour)
        val samples = List(512) { i ->
            val angle = i*2*PI/512
            val radius = (.08+(i%9)*.1)*profile.radiusAt(angle)
            BlobPoint(cos(angle)*radius,sin(angle)*radius)
        }
        fun finite(x: Double,y: Double): JellyNormal {
            val e = .004
            val dx = (profile.height(x+e,y)-profile.height(x-e,y))/(2*e)
            val dy = (profile.height(x,y+e)-profile.height(x,y-e))/(2*e)
            val length = sqrt(dx*dx+dy*dy+1)
            return JellyNormal(-dx/length,-dy/length,1/length)
        }
        var checksum = 0.0
        fun measure(analytic: Boolean): Long {
            val start = System.nanoTime()
            repeat(100) { for (point in samples) {
                val normal = if (analytic) profile.normal(point.x,point.y) else finite(point.x,point.y)
                checksum += normal.z
            } }
            return System.nanoTime()-start
        }
        repeat(2) { measure(false); measure(true) }
        val baseline = mutableListOf<Long>()
        val analytic = mutableListOf<Long>()
        repeat(5) { round ->
            // Alternate order to reduce warming and scheduling bias.
            if (round%2 == 0) { baseline += measure(false); analytic += measure(true) }
            else { analytic += measure(true); baseline += measure(false) }
        }
        assertTrue(checksum.isFinite() && checksum > 0)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null),"jelly-performance")
        check(directory.isDirectory || directory.mkdirs())
        File(directory,"analytic-lighting.json").writeText(
            """{"samplesPerRound":51200,"rounds":5,"baselineMedianNs":${baseline.sorted()[2]},"analyticMedianNs":${analytic.sorted()[2]},"baselineNs":$baseline,"analyticNs":$analytic,"checksum":$checksum}""",
        )
    }

    @Test fun nativeVolumeAndOlderAndroidMeshHaveDepthAndReactAtTheContact() {
        val useMesh = mutableStateOf(false)
        val pressure = mutableStateOf(0f)
        val touchX = mutableStateOf(.35f)
        val skin = mutableStateOf(SkinId.SKY_MEADOW)
        val backdrop = mutableStateOf<Color?>(null)
        val gpu = if (Build.VERSION.SDK_INT >= 33) GpuJellyVolumeMaterial() else MeshJellyVolumeMaterial()
        val mesh = MeshJellyVolumeMaterial()
        compose.setContent {
            Canvas(Modifier.size(260.dp).testTag("jelly-material")) {
                drawRect(backdrop.value ?: if (skin.value == SkinId.SHRINE) Color(0xFF0A1421) else Color(0xFFF4F0DC))
                val contour = List(64) { index ->
                    val angle = index*2*PI/64
                    val radius = size.width*.39*(1+.04*sin(angle*5))
                    BlobPoint(center.x+cos(angle)*radius, center.y+sin(angle)*radius*.94)
                }
                val path = Path().apply {
                    moveTo(contour[0].x.toFloat(),contour[0].y.toFloat())
                    contour.drop(1).forEach { lineTo(it.x.toFloat(),it.y.toFloat()) }
                    close()
                }
                val frame = JellyFrame(
                    JellyVolumeGeometry.profile(contour), Color(0xFF42C884), emptyList(), emptyList(),
                    Offset(size.width*touchX.value,size.height*.32f),pressure.value,skin.value, translucency = 1f,
                )
                (if (useMesh.value) mesh else gpu).draw(this,path,frame)
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null),"jelly-material-captures")
        check(directory.isDirectory || directory.mkdirs())
        for (fallback in listOf(false,true)) for (world in SkinId.entries) {
            compose.runOnIdle { useMesh.value=fallback; skin.value=world; pressure.value=0f; touchX.value=.35f }
            val resting = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            val px = IntArray(resting.width*resting.height)
            resting.getPixels(px,0,resting.width,0,0,resting.width,resting.height)
            val samples = mutableListOf<Int>()
            for (y in resting.height/3 until resting.height*2/3 step 4) for (x in resting.width/3 until resting.width*2/3 step 4) {
                samples += android.graphics.Color.green(px[y*resting.width+x])
            }
            assertTrue("The dome must have a visible lighting range", samples.max()-samples.min()>25)
            compose.runOnIdle { pressure.value=1f }
            val pressed = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            val altered = IntArray(px.size)
            pressed.getPixels(altered,0,pressed.width,0,0,pressed.width,pressed.height)
            val changed = px.indices.count { px[it] != altered[it] }
            assertTrue("A local press must bend the material lighting",changed>px.size*.005)
            compose.runOnIdle { touchX.value=.65f }
            val moved = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            assertTrue("The reflection must follow a moving touch", !moved.sameAs(pressed))
            compose.runOnIdle { pressure.value=0f }
            val released = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            assertTrue("Zero pressure restores the exact resting material",released.sameAs(resting))
            released.recycle()
            compose.runOnIdle { pressure.value=0f; backdrop.value=Color.Black }
            val darkBackdrop = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            compose.runOnIdle { backdrop.value=Color.White }
            val lightBackdrop = compose.onNodeWithTag("jelly-material").captureToImage().asAndroidBitmap()
            val middle = darkBackdrop.width / 2
            val transmission = android.graphics.Color.green(lightBackdrop.getPixel(middle,middle)) -
                android.graphics.Color.green(darkBackdrop.getPixel(middle,middle))
            assertTrue("The real backdrop must remain visible through the jelly body", transmission in 45..200)
            compose.runOnIdle { backdrop.value=null }
            darkBackdrop.recycle(); lightBackdrop.recycle()
            val kind = if (fallback) "mesh" else "native"
            File(directory,"$kind-${world.storageId}-rest.png").outputStream().use { resting.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(directory,"$kind-${world.storageId}-press.png").outputStream().use { pressed.compress(Bitmap.CompressFormat.PNG,100,it) }
            File(directory,"$kind-${world.storageId}-drag-light.png").outputStream().use { moved.compress(Bitmap.CompressFormat.PNG,100,it) }
            resting.recycle(); pressed.recycle(); moved.recycle()
        }
    }
}
