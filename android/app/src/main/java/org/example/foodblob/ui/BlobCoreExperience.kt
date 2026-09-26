package org.example.foodblob.ui

import org.example.foodblob.domain.BlobPoint
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.PI
import kotlin.math.sin

/** A held colour is only presentation. Release owns at most one ordinary add request. */
internal class BlobColorDrag {
    var color: FoodColor? = null
        private set
    var point = BlobPoint(0.0, 0.0)
        private set
    private var generation = -1

    fun begin(color: FoodColor, generation: Int, point: BlobPoint) {
        this.color = color
        this.generation = generation
        this.point = point
    }

    fun move(point: BlobPoint) {
        this.point = point
    }

    fun finish(currentGeneration: Int, outline: List<BlobPoint>): FoodColor? {
        val accepted = color?.takeIf { generation == currentGeneration && contains(point, outline) }
        cancel()
        return accepted
    }

    fun cancel() {
        color = null
        generation = -1
    }

    companion object {
        fun contains(point: BlobPoint, outline: List<BlobPoint>): Boolean {
            var inside = false
            if (outline.size < 3) return false
            var previous = outline.last()
            for (next in outline) {
                if (
                    (next.y > point.y) != (previous.y > point.y) &&
                    point.x < (previous.x - next.x) * (point.y - next.y) / (previous.y - next.y) + next.x
                ) inside = !inside
                previous = next
            }
            return inside
        }
    }
}

internal object CoreCalendar {
    fun cells(month: YearMonth, firstDay: DayOfWeek): List<LocalDate?> {
        val first = month.atDay(1)
        val leading = (first.dayOfWeek.value - firstDay.value + 7) % 7
        return List(leading) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    }
}

internal object CoreBlobMotion {
    fun removedColor(before: FoodCounts, after: FoodCounts): FoodColor? =
        FoodColor.entries.singleOrNull { before.applyDelta(it, -1) == after && before.count(it) > 0 }

    fun massTime(total: Int): Double = .88 + .40 * (total.coerceIn(0, 18) / 18.0)

    fun weightedAge(ageMs: Long, total: Int): Long =
        if (ageMs <= ConnectedBlobMotion.FLIGHT_MS) ageMs
        else ConnectedBlobMotion.FLIGHT_MS + ((ageMs - ConnectedBlobMotion.FLIGHT_MS) / massTime(total)).toLong()

    fun greeting(ageMs: Long): Double {
        if (ageMs !in 0..900) return 0.0
        val t = ageMs / 900.0
        return sin(t * PI * 2) * sin(t * PI) * .035
    }
}
