package org.example.foodblob.widget

import org.example.foodblob.ui.BlobAppearance
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.example.foodblob.domain.BlobColor
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.LivingBlob
import org.example.foodblob.domain.SkinId
import kotlin.math.roundToInt

/** Fixed subregions within the existing reserved blob area. Never used by action geometry. */
internal data class FoodWidgetBlobComposition(val body: RectF, val footer: RectF) {
    companion object {
        fun growthScale(total: Int, body: RectF): Float {
            val growth = LivingBlob.growthScale(total).toFloat()
            return if (minOf(body.width(), body.height()) < 60f) .30f + .70f * growth else growth
        }

        fun resolve(region: WidgetRect, presentation: WidgetPresentation): FoodWidgetBlobComposition {
            val footerHeight = minOf(region.heightDp,
                FoodWidgetArtworkRenderer.totalTextSize(presentation, 0) + 4f)
            val footerTop = region.bottomDp - footerHeight
            return FoodWidgetBlobComposition(
                RectF(region.leftDp, region.topDp, region.rightDp,
                    (footerTop - 2f).coerceAtLeast(region.topDp)),
                RectF(region.leftDp, footerTop, region.rightDp, region.bottomDp),
            )
        }
    }
}

internal object FoodWidgetArtworkRenderer {
    @Suppress("UNUSED_PARAMETER")
    fun totalTextSize(presentation: WidgetPresentation, total: Int): Float =
        if (presentation == WidgetPresentation.SMALL) 18f else 22f

    fun footerInk(skin: SkinId): Int =
        if (skin == SkinId.SHRINE) Color.WHITE else Color.rgb(31, 56, 41)

    fun render(
        counts: FoodCounts,
        variant: FoodWidgetVariant,
        geometry: ResolvedWidgetGeometry,
        density: Float,
        available: Boolean,
        translucency: Float = 0f,
    ): Bitmap {
        val safeDensity = density.coerceAtLeast(1f)
        val bitmap = Bitmap.createBitmap(
            (geometry.widthDp * safeDensity).roundToInt().coerceAtLeast(1),
            (geometry.heightDp * safeDensity).roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        val canvas = Canvas(bitmap)
        canvas.scale(safeDensity, safeDensity)
        val card = RectF(0f, 0f, geometry.widthDp, geometry.heightDp)
        val cardPath = Path().apply { addRoundRect(card, 22f, 22f, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(cardPath)
        drawWorld(canvas, card, variant.skin, variant.presentation)
        drawBlob(canvas, counts, variant.skin, variant.presentation, geometry, available, translucency)
        when (variant.presentation) {
            WidgetPresentation.SMALL -> drawSmallControls(canvas, counts, variant.skin, geometry, available)
            WidgetPresentation.MEDIUM -> drawMediumControls(canvas, counts, variant.skin, geometry, available)
        }
        if (!available) {
            canvas.drawColor(Color.argb(92, 30, 33, 35))
            drawCenteredText(canvas, "!", card.centerX(), card.centerY(), 34f, Color.WHITE)
        }
        canvas.restore()
        return bitmap
    }

    private fun drawWorld(
        canvas: Canvas,
        card: RectF,
        skin: SkinId,
        presentation: WidgetPresentation,
    ) {
        if (skin == SkinId.SKY_MEADOW) {
            val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    card.width(),
                    card.height(),
                    intArrayOf(
                        Color.rgb(186, 224, 240),
                        Color.rgb(252, 235, 194),
                        Color.rgb(214, 217, 163),
                    ),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(card, sky)
            val sunRadius = card.height() * 0.22f
            val sunX = card.width() * 0.18f
            val sunY = card.height() * 0.20f
            canvas.drawCircle(
                sunX,
                sunY,
                sunRadius,
                solidPaint(Color.argb(117, 255, 240, 174)).apply {
                    maskFilter = BlurMaskFilter(9f, BlurMaskFilter.Blur.NORMAL)
                },
            )
            canvas.drawOval(
                RectF(
                    card.width() * 0.36f,
                    card.height() * 0.76f,
                    card.width() * 1.22f,
                    card.height() * 1.10f,
                ),
                solidPaint(Color.argb(220, 120, 173, 120)),
            )
            canvas.drawOval(
                RectF(
                    -card.width() * 0.23f,
                    card.height() * 0.84f,
                    card.width() * 0.59f,
                    card.height() * 1.13f,
                ),
                solidPaint(Color.argb(218, 97, 158, 110)),
            )
            canvas.drawOval(
                RectF(-card.width() * 0.25f, card.height() * 0.91f,
                    card.width() * 1.21f, card.height() * 1.27f),
                solidPaint(Color.argb(179, 79, 140, 97)),
            )
        } else {
            val centerX = if (presentation == WidgetPresentation.MEDIUM) card.width() * 0.24f else card.centerX()
            val centerY = card.height() * 0.44f
            canvas.drawRect(
                card,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        centerX,
                        centerY,
                        maxOf(card.width(), card.height()) * 0.78f,
                        intArrayOf(
                            Color.rgb(20, 71, 89),
                            Color.rgb(13, 43, 64),
                            Color.rgb(10, 28, 46),
                        ),
                        floatArrayOf(0f, 0.58f, 1f),
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            canvas.drawCircle(
                centerX,
                centerY,
                card.height() * 0.40f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        centerX,
                        centerY,
                        card.height() * 0.40f,
                        Color.argb(34, 87, 232, 199),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP,
                    )
                },
            )
            listOf(
                Triple(card.width() * 0.16f, card.height() * 0.16f, 1.6f),
                Triple(card.width() * 0.72f, card.height() * 0.19f, 1.05f),
                Triple(card.width() * 0.88f, card.height() * 0.54f, 1.4f),
            ).forEach { (x, y, radius) ->
                canvas.drawCircle(x, y, radius, solidPaint(Color.argb(190, 199, 245, 237)))
            }
            canvas.drawRect(
                0f, card.height() * 0.70f, card.width(), card.height(),
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, card.height() * 0.70f, 0f, card.height(),
                        Color.TRANSPARENT, Color.argb(230, 8, 23, 38), Shader.TileMode.CLAMP,
                    )
                },
            )
            canvas.drawRoundRect(
                RectF(0.5f, 0.5f, card.width() - 0.5f, card.height() - 0.5f),
                22f,
                22f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                    color = Color.argb(64, 128, 232, 219)
                },
            )
        }
    }

    private fun drawBlob(
        canvas: Canvas,
        counts: FoodCounts,
        skin: SkinId,
        presentation: WidgetPresentation,
        geometry: ResolvedWidgetGeometry,
        available: Boolean,
        translucency: Float,
    ) {
        val reserved = geometry.blobRect ?: return
        val region = RectF(reserved.leftDp, reserved.topDp, reserved.rightDp, reserved.bottomDp)
        if (region.width() <= 0f || region.height() <= 0f) return
        canvas.save()
        canvas.clipRect(region)
        // Apple sizes the resting puddle against its maximum growth, so all
        // states keep a clear margin without clipping or moving any controls.
        val composition = FoodWidgetBlobComposition.resolve(reserved, presentation)
        val bodyRegion = composition.body
        val maximumGrowth = LivingBlob.maximumGrowthScale.toFloat()
        val naturalWidth = if (presentation == WidgetPresentation.SMALL) 92f else 124f
        val naturalHeight = if (presentation == WidgetPresentation.SMALL) 86f else 118f
        val fit = minOf(1f, minOf(bodyRegion.width() / naturalWidth,
            bodyRegion.height() / naturalHeight) / maximumGrowth)
        val blobWidth = naturalWidth * fit
        val blobHeight = naturalHeight * fit
        val growth = FoodWidgetBlobComposition.growthScale(counts.total, bodyRegion)
        val centerY = bodyRegion.top + bodyRegion.height() * .84f - blobHeight * growth * .34f
        val blobRect = RectF(
            bodyRegion.centerX() - blobWidth / 2f,
            centerY - blobHeight / 2f,
            bodyRegion.centerX() + blobWidth / 2f,
            centerY + blobHeight / 2f,
        )
        canvas.save()
        canvas.clipRect(bodyRegion)
        // Shadow and pigment spill share the upper-left key light and remain
        // bounded even in a launcher's shortest compatibility layout.
        if (blobWidth > 0f && blobHeight > 0f) {
            drawGroundLight(canvas, blobRect.centerX(),
                blobRect.centerY() + blobHeight * growth * .34f,
                blobWidth * growth * .68f, blobHeight * growth * .12f,
                if (skin == SkinId.SKY_MEADOW) Color.argb(72, 28, 56, 33) else Color.argb(97, 0, 0, 0))
            BlobColor.mix(counts)?.let { tint ->
                drawGroundLight(canvas, blobRect.centerX() + blobWidth * growth * .07f,
                    blobRect.centerY() + blobHeight * growth * .35f,
                    blobWidth * growth * .78f, blobHeight * growth * .18f,
                    Color.argb(((if (skin == SkinId.SHRINE) 61 else 36) * BlobAppearance.normalize(translucency)).roundToInt(),
                        (tint.red * 255).roundToInt(), (tint.green * 255).roundToInt(),
                        (tint.blue * 255).roundToInt()))
            }
        }
        val gel = FoodWidgetGelSnapshot.render(counts, skin == SkinId.SHRINE, 256, 256, translucency)
        val gelRect = RectF(
            blobRect.centerX() - blobRect.width() * growth / 2f,
            blobRect.centerY() - blobRect.height() * growth / 2f,
            blobRect.centerX() + blobRect.width() * growth / 2f,
            blobRect.centerY() + blobRect.height() * growth / 2f,
        )
        canvas.drawBitmap(gel, null, gelRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        drawCenteredText(
            canvas,
            if (available) counts.total.toString() else "–",
            composition.footer.centerX(),
            composition.footer.centerY(),
            totalTextSize(presentation, counts.total),
            footerInk(skin),
            maxWidth = composition.footer.width() - 4f,
        )
        canvas.restore()
    }

    private fun drawSmallControls(
        canvas: Canvas,
        counts: FoodCounts,
        skin: SkinId,
        geometry: ResolvedWidgetGeometry,
        available: Boolean,
    ) {
        geometry.actionTargets.forEach { target ->
            val color = target.color
            val rect = target.rect
            val visual = RectF(
                rect.leftDp,
                rect.topDp + 4f,
                rect.rightDp,
                rect.bottomDp - 4f,
            )
            canvas.drawRoundRect(visual, visual.height() / 2f, visual.height() / 2f,
                solidPaint(if (skin == SkinId.SKY_MEADOW) Color.argb(26, 0, 0, 0)
                    else withAlpha(colorInt(color), 66)).apply {
                    maskFilter = BlurMaskFilter(if (skin == SkinId.SKY_MEADOW) 3f else 5f,
                        BlurMaskFilter.Blur.NORMAL)
                })
            drawColorControl(canvas, visual, color, skin, enabled = available)
            if (geometry.blobRect == null) {
                // Short launcher rows have no room for a total-bearing puddle.
                // Show each authoritative count so a successful add is visible.
                drawCenteredText(canvas, counts.count(color).toString(),
                    visual.centerX() - 7f, visual.centerY(), 23f,
                    controlForeground(skin, color, available), maxWidth = visual.width() - 28f)
                drawPlus(canvas, visual.right - 12f, visual.centerY(), skin, color, available)
            } else {
                drawPlus(canvas, visual.centerX(), visual.centerY(), skin, color, available)
            }
        }
    }

    private fun drawMediumControls(
        canvas: Canvas,
        counts: FoodCounts,
        skin: SkinId,
        geometry: ResolvedWidgetGeometry,
        available: Boolean,
    ) {
        geometry.actionTargets.forEach { target ->
            val color = target.color
            val rect = target.rect
            val touchRect = RectF(rect.leftDp, rect.topDp, rect.rightDp, rect.bottomDp)
            if (target.delta > 0) {
                val addRect = touchRect
                drawColorControl(canvas, addRect, color, skin, enabled = available)
                drawCenteredText(
                    canvas,
                    counts.count(color).toString(),
                    addRect.centerX(),
                    addRect.centerY(),
                    23f,
                    controlForeground(skin, color, available),
                    maxWidth = (addRect.width() - 20f).coerceAtLeast(20f),
                )
            } else {
                val minusTouch = touchRect
                val enabled = available && counts.count(color) > 0
                val radius = 16f
                val fill = if (skin == SkinId.SKY_MEADOW) {
                    Color.argb(if (enabled) 176 else 76, 255, 255, 255)
                } else {
                    Color.argb(if (enabled) 28 else 13, 128, 232, 219)
                }
                canvas.drawCircle(minusTouch.centerX(), minusTouch.centerY(), radius, solidPaint(fill))
                val minusColor = if (skin == SkinId.SKY_MEADOW) {
                    Color.argb(if (enabled) 230 else 90, 107, 92, 66)
                } else {
                    Color.argb(if (enabled) 190 else 76, 235, 245, 245)
                }
                canvas.drawRoundRect(
                    minusTouch.centerX() - 6f,
                    minusTouch.centerY() - 1.25f,
                    minusTouch.centerX() + 6f,
                    minusTouch.centerY() + 1.25f,
                    1.25f,
                    1.25f,
                    solidPaint(minusColor),
                )
            }
        }
    }

    private fun drawColorControl(
        canvas: Canvas,
        rect: RectF,
        foodColor: FoodColor,
        skin: SkinId,
        enabled: Boolean,
    ) {
        val base = colorInt(foodColor)
        val alpha = if (enabled) 255 else 112
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = if (skin == SkinId.SKY_MEADOW) {
                LinearGradient(
                    rect.left,
                    rect.top,
                    rect.right,
                    rect.bottom,
                    withAlpha(meadowGradient(foodColor).first, alpha),
                    withAlpha(meadowGradient(foodColor).second, alpha),
                    Shader.TileMode.CLAMP,
                )
            } else {
                LinearGradient(
                    rect.left,
                    rect.top,
                    rect.left,
                    rect.bottom,
                    withAlpha(base, if (enabled) 69 else 28),
                    withAlpha(base, if (enabled) 28 else 11),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        val lightRect = RectF(rect).apply { inset(.45f, .45f) }
        canvas.drawRoundRect(lightRect, lightRect.height() / 2f, lightRect.height() / 2f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = .9f
                shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                    intArrayOf(Color.argb(if (enabled) 117 else 44, 255, 255, 255),
                        Color.TRANSPARENT, Color.argb(23, 0, 0, 0)),
                    floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            })
    }

    private fun drawPlus(
        canvas: Canvas,
        x: Float,
        y: Float,
        skin: SkinId,
        foodColor: FoodColor,
        enabled: Boolean,
    ) {
        val color = controlForeground(skin, foodColor, enabled)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(x - 6f, y, x + 6f, y, paint)
        canvas.drawLine(x, y - 6f, x, y + 6f, paint)
    }

    private fun controlForeground(
        skin: SkinId,
        foodColor: FoodColor,
        enabled: Boolean,
    ): Int {
        val base = if (skin == SkinId.SHRINE) {
            Color.WHITE
        } else {
            Color.rgb(26, 31, 26)
        }
        return withAlpha(base, if (enabled) 244 else 110)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        centerY: Float,
        size: Float,
        color: Int,
        maxWidth: Float = Float.POSITIVE_INFINITY,
        shadowColor: Int = Color.TRANSPARENT,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = size
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            this.color = color
            if (shadowColor != Color.TRANSPARENT) setShadowLayer(1f, 0f, 0.5f, shadowColor)
        }
        val measured = paint.measureText(text)
        if (measured > maxWidth) paint.textSize *= maxWidth.coerceAtLeast(1f) / measured
        canvas.drawText(text, centerX, centerY - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawGroundLight(canvas: Canvas, x: Float, y: Float,
        width: Float, height: Float, tint: Int) {
        if (width <= 0f || height <= 0f) return
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(1f, height / width)
        canvas.drawCircle(0f, 0f, width / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(0f, 0f, width / 2f, tint, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.restore()
    }

    private fun solidPaint(color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun colorInt(color: FoodColor): Int = when (color) {
        FoodColor.GREEN -> Color.rgb(33, 209, 112)
        FoodColor.YELLOW -> Color.rgb(252, 201, 46)
        FoodColor.RED -> Color.rgb(245, 77, 89)
    }

    private fun meadowGradient(color: FoodColor): Pair<Int, Int> = when (color) {
        FoodColor.GREEN -> Color.rgb(56, 219, 135) to Color.rgb(26, 176, 89)
        FoodColor.YELLOW -> Color.rgb(255, 222, 92) to Color.rgb(245, 184, 26)
        FoodColor.RED -> Color.rgb(255, 110, 120) to Color.rgb(227, 56, 74)
    }

    private fun withAlpha(color: Int, alpha: Int): Int = Color.argb(
        alpha.coerceIn(0, 255),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )
}

/** Static CPU counterpart of the app's domed gel material. No widget animation loop.
 * At most four 256px bodies (1 MB) are retained; controls are still drawn natively. */
internal object FoodWidgetGelSnapshot {
    private val cache = object : android.util.LruCache<String, Bitmap>(1_048_576) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun render(counts: FoodCounts, shrine: Boolean, width: Int, height: Int, translucency: Float = 0f): Bitmap {
        val transmission = BlobAppearance.normalize(translucency).toDouble()
        val w = width.coerceIn(1, 256)
        val h = height.coerceIn(1, 256)
        val key = "${counts.green}:${counts.yellow}:${counts.red}:$shrine:$w:$h:$transmission"
        cache.get(key)?.let { return it }
        val points = LivingBlob.samplePoints(counts, tapSeed = counts.total, boost = 0.0)
        val radii = points.map { kotlin.math.hypot(it.x - 50.0, it.y - 52.0) }
        val base = BlobColor.mix(counts)
        val channels = doubleArrayOf(base?.red ?: 0.773, base?.green ?: 0.898, base?.blue ?: 0.847)
        val rimColor = if (shrine) doubleArrayOf(0.66, 0.96, 0.91) else doubleArrayOf(0.97, 1.0, 0.88)
        val causticColor = doubleArrayOf(1.0, 0.92, 0.60).mapIndexed { i, light ->
            channels[i] * .65 + light * .35
        }
        // These terms depend only on thickness. A tiny linearly interpolated
        // table removes four transcendental calls per covered pixel, without
        // retaining another bitmap or any cross-render state.
        val optics = DoubleArray(256 * 4)
        for (step in 0..255) {
            val z = step / 255.0
            for (channel in 0..2) {
                optics[step * 4 + channel] = kotlin.math.exp(
                    -((1 - channels[channel]) * 1.35 + 0.12) * (0.25 + z * 1.25),
                )
            }
            optics[step * 4 + 3] = 0.025 + 0.34 * Math.pow(1 - z, 2.3)
        }
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val py = (y + 0.5) / h * 100 - 50
            for (x in 0 until w) {
                val px = (x + 0.5) / w * 100 - 50
                val angle = (kotlin.math.atan2(py, px) + 2 * Math.PI) % (2 * Math.PI)
                val index = angle / (2 * Math.PI) * radii.size
                val low = index.toInt() % radii.size
                val fraction = index - kotlin.math.floor(index)
                val boundary = radii[low] * (1 - fraction) + radii[(low + 1) % radii.size] * fraction
                val nx = px / boundary.coerceAtLeast(1.0)
                val ny = py / boundary.coerceAtLeast(1.0)
                val distance = kotlin.math.sqrt(px * px + py * py)
                val radius = distance / boundary.coerceAtLeast(1.0)
                val coverage = ((1 - radius) * minOf(w, h) * 0.38).coerceIn(0.0, 1.0)
                if (coverage <= 0) continue
                val slope = (radii[(low + 1) % radii.size] - radii[low]) * radii.size / (2 * Math.PI)
                val slopeRatio = slope / boundary.coerceAtLeast(1.0)
                val inset = (boundary - distance).coerceAtLeast(0.0) /
                    kotlin.math.sqrt(1 + slopeRatio * slopeRatio)
                val z = kotlin.math.sqrt((1 - kotlin.math.exp(-inset / 10)).coerceAtLeast(0.0))
                val diffuse = (-0.40 * nx - 0.55 * ny + 0.73 * z).coerceAtLeast(0.0)
                val tablePosition = z * 255
                val tableIndex = tablePosition.toInt().coerceAtMost(254)
                val blend = tablePosition - tableIndex
                fun optical(channel: Int): Double {
                    val lowValue = optics[tableIndex * 4 + channel]
                    return lowValue + (optics[(tableIndex + 1) * 4 + channel] - lowValue) * blend
                }
                val fresnel = optical(3)
                val windowX = (nx + 0.32 + ny * 0.10) / 0.25
                val windowY = (ny + 0.48 - nx * nx * 0.16) / 0.23
                val windowRadius = windowX * windowX + windowY * windowY
                val window = kotlin.math.exp(-windowRadius * windowRadius) * 0.72
                val sheenY = (ny + .60 - nx * nx * .18) / .038
                val sheenX = (nx + .25) / .30
                val sheen = kotlin.math.exp(-sheenY * sheenY - Math.pow(sheenX, 4.0)) * .20
                val causticY = (ny - .67 + nx * nx * .07) / .075
                val causticX = nx / .62
                val caustic = kotlin.math.exp(-causticY * causticY - causticX * causticX) * .26
                val rim = fresnel * if (shrine) 0.97 else 0.64
                val glassAlpha = minOf(.78, .31 + z * .19 + window * .32 + fresnel * .50)
                val alpha = coverage * (1 + (glassAlpha - 1) * transmission)
                fun channel(i: Int): Int {
                    val transmitted = optical(i)
                    var value = channels[i] * (if (shrine) .39 + .44 * diffuse else .34 + .48 * diffuse) +
                        transmitted * (if (shrine) .29 else .27)
                    value = value * (1 - window) + window
                    value += sheen * (1 - value)
                    value += rim * rimColor[i] + caustic * causticColor[i]
                    val wet = window * .16
                    val paint = (channels[i] * (.56 + .48 * diffuse) * (1 - wet) + wet).coerceIn(0.0, 1.0)
                    val material = paint + (value.coerceIn(0.0, 1.0) - paint) * transmission
                    return (material * 255).roundToInt()
                }
                pixels[y * w + x] = Color.argb((alpha * 255).roundToInt(), channel(0), channel(1), channel(2))
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { cache.put(key, it) }
    }
}
