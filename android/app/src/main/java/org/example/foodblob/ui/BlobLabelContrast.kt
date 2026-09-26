package org.example.foodblob.ui

import androidx.compose.ui.graphics.Color
import org.example.foodblob.domain.BlobColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

internal fun blobLabelBackdrop(skin: SkinId): Color =
    if (skin == SkinId.SHRINE) Color(0xFF12293B) else Color(0xFFF6EDCE)

/** Settled flat-centre estimate of JellyVolumeMaterial, including real backdrop transmission.
 * The label uses this stable estimate rather than chasing individual ripple highlights.
 */
internal fun predictedBlobCore(counts: FoodCounts, skin: SkinId, backdrop: Color): Color {
    val pigment = BlobColor.mix(counts) ?: BlobColor.EMPTY
    val diffuse = .69 / sqrt(.44*.44 + .57*.57 + .69*.69)
    val window = exp(-(.42/.38).pow(4) - (.48/.43).pow(4)) * .36
    val side = exp(-(.80/.22).pow(2) - (.04/.50).pow(2)) * .16
    val fresnel = .025
    val alpha = .20 + .32 + minOf(.16, fresnel + window*.24 + side*.2)
    fun channel(value: Double, cool: Double, background: Float): Float {
        val transmitted = exp(-((1-value)*1.8 + .16))
        val material = (value*(.32+.42*diffuse) + transmitted*.31 + fresnel*cool + window + side*cool).coerceIn(0.0,1.0)
        return (material*alpha + background*(1-alpha)).toFloat()
    }
    return Color(
        channel(pigment.red, if (skin == SkinId.SHRINE) .32 else .65, backdrop.red),
        channel(pigment.green, .89, backdrop.green),
        channel(pigment.blue, 1.0, backdrop.blue),
    )
}

internal fun blobLabelContrast(a: Color, b: Color): Double {
    fun luminance(color: Color): Double {
        fun linear(channel: Float): Double = channel.toDouble().let {
            if (it <= .04045) it / 12.92 else ((it+.055)/1.055).pow(2.4)
        }
        return .2126*linear(color.red) + .7152*linear(color.green) + .0722*linear(color.blue)
    }
    val first = luminance(a)
    val second = luminance(b)
    return (maxOf(first,second)+.05)/(minOf(first,second)+.05)
}

internal fun blobContentColor(counts: FoodCounts, skin: SkinId, backdrop: Color = blobLabelBackdrop(skin)): Color {
    val body = predictedBlobCore(counts, skin, backdrop)
    return if (blobLabelContrast(Color.Black,body) >= blobLabelContrast(Color.White,body)) Color.Black else Color.White
}
