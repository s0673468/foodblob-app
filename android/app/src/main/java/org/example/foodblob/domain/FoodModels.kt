package org.example.foodblob.domain

import java.security.MessageDigest
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FoodColor(val storageId: String) {
    GREEN("green"),
    YELLOW("yellow"),
    RED("red");

    companion object {
        fun fromStorage(value: String): FoodColor? = entries.firstOrNull { it.storageId == value }
    }
}

class FoodCounts(green: Int = 0, yellow: Int = 0, red: Int = 0) {
    val green: Int = green.coerceAtLeast(0)
    val yellow: Int = yellow.coerceAtLeast(0)
    val red: Int = red.coerceAtLeast(0)

    val total: Int get() = (green.toLong() + yellow + red).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val isEmpty: Boolean get() = total == 0

    fun count(color: FoodColor): Int = when (color) {
        FoodColor.GREEN -> green
        FoodColor.YELLOW -> yellow
        FoodColor.RED -> red
    }

    fun applyDelta(color: FoodColor, delta: Int): FoodCounts = when (color) {
        FoodColor.GREEN -> FoodCounts(green.saturatedPlus(delta), yellow, red)
        FoodColor.YELLOW -> FoodCounts(green, yellow.saturatedPlus(delta), red)
        FoodColor.RED -> FoodCounts(green, yellow, red.saturatedPlus(delta))
    }

    override fun equals(other: Any?): Boolean =
        other is FoodCounts && green == other.green && yellow == other.yellow && red == other.red

    override fun hashCode(): Int = 31 * (31 * green + yellow) + red

    override fun toString(): String = "FoodCounts(green=$green, yellow=$yellow, red=$red)"
}

private fun Int.saturatedPlus(other: Int): Int =
    (toLong() + other.toLong()).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

enum class SkinId(val storageId: String) {
    SKY_MEADOW("sky_meadow"),
    SHRINE("shrine");

    companion object {
        fun fromStorage(value: String?): SkinId = entries.firstOrNull { it.storageId == value } ?: SKY_MEADOW
    }
}

enum class WidgetLayoutId(val storageId: String) {
    BUBBLE_STACK("bubble_stack"),
    POP_COLUMNS("pop_columns"),
    BLOB_STAGE("blob_stage"),
    FOUR_POPS("four_pops"),
    TAP_DECK("tap_deck"),
    SIDECAR_TILES("sidecar_tiles"),
    DICE_ROW("dice_row"),
    COLOR_COURTYARD("color_courtyard"),
    STEP_STONES("step_stones"),
    PUDDLE_DOCK("puddle_dock"),
    PALETTE_TRAY("palette_tray");

    companion object {
        fun fromStorage(value: String?): WidgetLayoutId =
            entries.firstOrNull { it.storageId == value } ?: BUBBLE_STACK
    }
}

data class DayRecord(
    val dateKey: String,
    val counts: FoodCounts,
    val updatedAt: Instant,
)

data class UndoAction(
    val id: Long = 0,
    val dateKey: String,
    val color: FoodColor,
    val delta: Int,
    val createdAt: Instant,
)

data class WidgetEvent(
    val id: UUID,
    val occurredAt: Instant,
    val dateKey: String,
    val zoneId: String,
    val color: FoodColor,
    val delta: Int,
)

object FoodDateKeys {
    fun forInstant(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String =
        instant.atZone(zoneId).toLocalDate().toString()

    fun parse(value: String): LocalDate? {
        val parts = value.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    fun canonical(value: String): String? = parse(value)?.toString()

    fun isCanonical(value: String): Boolean = canonical(value) == value
}

data class BlobColor(val red: Double, val green: Double, val blue: Double) {
    val relativeLuminance: Double get() = 0.2126 * red + 0.7152 * green + 0.0722 * blue

    private data class Lab(val lightness: Double, val a: Double, val b: Double)

    companion object {
        val FOOD_GREEN = BlobColor(0.130, 0.820, 0.440)
        val FOOD_YELLOW = BlobColor(0.990, 0.790, 0.180)
        val FOOD_RED = BlobColor(0.960, 0.300, 0.350)
        val EMPTY = BlobColor(0.50, 0.52, 0.56)

        private val greenLab = linearToOklab(srgbToLinear(FOOD_GREEN))
        private val yellowLab = linearToOklab(srgbToLinear(FOOD_YELLOW))
        private val redLab = linearToOklab(srgbToLinear(FOOD_RED))

        fun mix(counts: FoodCounts): BlobColor? {
            if (counts.total <= 0) return null
            val total = counts.total.toDouble()
            val weighted = listOf(
                counts.green / total to greenLab,
                counts.yellow / total to yellowLab,
                counts.red / total to redLab,
            )
            var l = 0.0
            var a = 0.0
            var b = 0.0
            weighted.forEach { (weight, lab) ->
                if (weight > 0) {
                    l += lab.lightness * weight
                    a += lab.a * weight
                    b += lab.b * weight
                }
            }
            return linearToSrgb(oklabToLinear(Lab(l, a, b)))
        }

        private fun srgbToLinear(color: BlobColor): BlobColor {
            fun channel(value: Double): Double =
                if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            return BlobColor(channel(color.red), channel(color.green), channel(color.blue))
        }

        private fun linearToSrgb(color: BlobColor): BlobColor {
            fun channel(value: Double): Double {
                val clamped = value.coerceIn(0.0, 1.0)
                return if (clamped <= 0.0031308) {
                    clamped * 12.92
                } else {
                    1.055 * clamped.pow(1.0 / 2.4) - 0.055
                }
            }
            return BlobColor(channel(color.red), channel(color.green), channel(color.blue))
        }

        private fun linearToOklab(color: BlobColor): Lab {
            val l = 0.4122214708 * color.red + 0.5363325363 * color.green + 0.0514459929 * color.blue
            val m = 0.2119034982 * color.red + 0.6806995451 * color.green + 0.1073969566 * color.blue
            val s = 0.0883024619 * color.red + 0.2817188376 * color.green + 0.6299787005 * color.blue
            val lRoot = Math.cbrt(l)
            val mRoot = Math.cbrt(m)
            val sRoot = Math.cbrt(s)
            return Lab(
                lightness = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
                a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
                b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot,
            )
        }

        private fun oklabToLinear(color: Lab): BlobColor {
            val lRoot = color.lightness + 0.3963377774 * color.a + 0.2158037573 * color.b
            val mRoot = color.lightness - 0.1055613458 * color.a - 0.0638541728 * color.b
            val sRoot = color.lightness - 0.0894841775 * color.a - 1.2914855480 * color.b
            val l = lRoot * lRoot * lRoot
            val m = mRoot * mRoot * mRoot
            val s = sRoot * sRoot * sRoot
            return BlobColor(
                red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
            )
        }
    }
}

data class BlobPoint(val x: Double, val y: Double)

object LivingBlob {
    const val maximumGrowthScale: Double = 1.1806
    const val maximumHeroGrowthScale: Double = 1.3744
    // Keep these milestones and geometry identical to Shared/LivingBlob.swift.
    private fun scale(total: Int, anchors: List<Double>, limit: Double): Double {
        val count = total.coerceAtLeast(0)
        if (count <= 3) return anchors[count]
        if (count <= 10) return anchors[3] + (anchors[4] - anchors[3]) * (count - 3) / 7
        val later = (count - 10).toDouble()
        return anchors[4] + (limit - anchors[4]) * later / (later + 18)
    }

    fun growthScale(total: Int): Double =
        scale(total, listOf(0.34, 0.40, 0.50, 0.55, 1.04), maximumGrowthScale)

    fun heroGrowthScale(total: Int): Double =
        scale(total, listOf(0.40, 0.48, 0.56, 0.66, 1.17), maximumHeroGrowthScale)

    fun derivedTapSeed(counts: FoodCounts): Int = counts.total

    // Rounded overlapping volumes, sampled once rather than on every frame.
    private val forms = listOf(
        listOf(doubleArrayOf(0.0, 0.0, 0.9, 0.86)),
        listOf(doubleArrayOf(0.0, 0.25, 0.73, 0.78), doubleArrayOf(-0.13, -0.42, 0.48, 0.61)),
        listOf(doubleArrayOf(-0.39, -0.22, 0.65, 0.63), doubleArrayOf(0.3, 0.28, 0.74, 0.59)),
        listOf(doubleArrayOf(0.0, -0.36, 0.61, 0.62), doubleArrayOf(-0.4, 0.29, 0.62, 0.59), doubleArrayOf(0.4, 0.29, 0.62, 0.59)),
        listOf(doubleArrayOf(0.0, 0.22, 0.78, 0.74), doubleArrayOf(-0.48, -0.5, 0.38, 0.39), doubleArrayOf(0.48, -0.5, 0.38, 0.39)),
        listOf(doubleArrayOf(-0.57, 0.15, 0.5, 0.57), doubleArrayOf(0.0, -0.18, 0.58, 0.71), doubleArrayOf(0.57, 0.15, 0.5, 0.57)),
        listOf(doubleArrayOf(-0.37, -0.32, 0.56, 0.56), doubleArrayOf(0.37, -0.32, 0.56, 0.56), doubleArrayOf(-0.37, 0.39, 0.53, 0.51), doubleArrayOf(0.37, 0.39, 0.53, 0.51)),
        listOf(doubleArrayOf(0.0, -0.57, 0.43, 0.48), doubleArrayOf(0.0, -0.05, 0.66, 0.56), doubleArrayOf(0.0, 0.47, 0.88, 0.47)),
        listOf(doubleArrayOf(0.0, -0.25, 0.97, 0.6), doubleArrayOf(0.0, 0.39, 0.53, 0.65)),
        listOf(doubleArrayOf(-0.29, 0.06, 0.86, 0.64), doubleArrayOf(0.59, -0.15, 0.46, 0.43)),
        listOf(doubleArrayOf(0.0, 0.2, 0.7, 0.84), doubleArrayOf(-0.39, -0.63, 0.39, 0.4), doubleArrayOf(0.39, -0.63, 0.39, 0.4), doubleArrayOf(-0.57, 0.29, 0.35, 0.43), doubleArrayOf(0.57, 0.29, 0.35, 0.43)),
    )
    private val archetypes: List<List<Double>> = forms.map { shape ->
        var rounded = normalized((0 until 48).map { index ->
            val angle = index.toDouble() / 48 * 2 * PI
            var low = 0.0
            var high = 2.5
            repeat(23) {
                val radius = (low + high) / 2
                val x = cos(angle) * radius
                val y = sin(angle) * radius
                val field = shape.sumOf { ellipse ->
                    val dx = (x - ellipse[0]) / ellipse[2]
                    val dy = (y - ellipse[1]) / ellipse[3]
                    kotlin.math.exp(5 * (1 - dx * dx - dy * dy))
                }
                if (field > 1) low = radius else high = radius
            }
            (low + high) / 2
        }, 0.88)
        repeat(20) {
            val previous = rounded
            rounded = (0 until 48).map { (previous[(it + 47) % 48] + 2 * previous[it] + previous[(it + 1) % 48]) / 4 }
        }
        rounded
    }
    private val secondHarmonic = ((1 + cos(4 * PI / 48)) / 2).pow(20)
    private val thirdHarmonic = ((1 + cos(6 * PI / 48)) / 2).pow(20)

    private fun normalized(radii: List<Double>, radius: Double): List<Double> {
        val area = radii.indices.sumOf { radii[it] * radii[(it + 1) % 48] * sin(2 * PI / 48) / 2 }
        val factor = sqrt(PI * radius * radius / area)
        return radii.map { it * factor }
    }

    fun samplePoints(counts: FoodCounts, tapSeed: Int, boost: Double): List<BlobPoint> {
        val total = counts.total.coerceAtLeast(0)
        val base = if (total <= 10) archetypes[total] else {
            val cycle = listOf(10, 5, 8, 6, 9, 3, 7, 4)
            val step = total - 10
            val index = (step / 4) % cycle.size
            val t = (step % 4).toDouble() / 4
            val blend = t * t * (3 - 2 * t)
            val from = archetypes[cycle[index]]
            val to = archetypes[cycle[(index + 1) % cycle.size]]
            (0 until 48).map { from[it] + (to[it] - from[it]) * blend }
        }
        // Modular arithmetic matches Swift without overflow or redraw-time RNG.
        val seed = ((counts.green % 65521) * 31 + (counts.yellow % 65521) * 57 +
            (counts.red % 65521) * 97 + ((tapSeed % 65521 + 65521) % 65521) * 17 + 19) % 65521
        val phase = seed.toDouble() / 65521 * 2 * PI
        var radii = (0 until 48).map { index ->
            val angle = index.toDouble() / 48 * 2 * PI
            val variation = 0.014 * secondHarmonic * sin(2 * angle + phase * 7) + 0.009 * thirdHarmonic * cos(3 * angle - phase * 11)
            0.88 + (base[index] - 0.88) * 0.8 + variation
        }
        radii = normalized(radii, 37.0)
        // Leave deformation headroom, retaining equal area across silhouettes.
        repeat(8) {
            if (radii.max() > 45.5) radii = normalized(radii.map { 37 + (it - 37) * 0.94 }, 37.0)
        }
        val amount = boost.coerceIn(0.0, 1.0)
        return radii.mapIndexed { index, radius ->
            val angle = index.toDouble() / 48 * 2 * PI
            val squashed = radius * (1 + amount * 0.055 * cos(2 * angle))
            BlobPoint(50 + cos(angle) * squashed, 52 + sin(angle) * squashed)
        }
    }
}

object FoodStreak {
    fun consecutiveDays(endingAt: LocalDate, records: Map<String, FoodCounts>): Int {
        var streak = 0
        repeat(180) { offset ->
            val key = endingAt.minusDays(offset.toLong()).toString()
            if ((records[key]?.total ?: 0) <= 0) return streak
            streak += 1
        }
        return streak
    }
}

object LedgerProjection {
    fun effectiveCounts(
        canonical: Map<String, FoodCounts>,
        events: Iterable<WidgetEvent>,
        consumedIds: Set<UUID>,
    ): Map<String, FoodCounts> {
        val result = canonical.toMutableMap()
        val seen = mutableSetOf<UUID>()
        events.forEach { event ->
            if (event.id in consumedIds || !seen.add(event.id)) return@forEach
            if (event.delta == 0 || !FoodDateKeys.isCanonical(event.dateKey)) return@forEach
            result[event.dateKey] = (result[event.dateKey] ?: FoodCounts()).applyDelta(event.color, event.delta)
        }
        return result
    }
}

object SnapshotRevision {
    fun forRows(rows: Map<String, FoodCounts>): String {
        val canonical = buildString {
            rows.toSortedMap().forEach { (date, counts) ->
                append(date)
                append(':')
                append(counts.green)
                append(',')
                append(counts.yellow)
                append(',')
                append(counts.red)
                append(';')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
    }
}
