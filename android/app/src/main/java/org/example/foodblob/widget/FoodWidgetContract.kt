package org.example.foodblob.widget

import org.example.foodblob.domain.FoodColor
import org.example.foodblob.domain.FoodCounts
import org.example.foodblob.domain.SkinId
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal enum class WidgetPresentation {
    SMALL,
    MEDIUM,
}

internal enum class WidgetLayout {
    QUICK_WIDE,
    QUICK_TALL,
    QUICK_GRID,
    QUICK_FEATURED,
    FULL_COLUMNS,
    FULL_ROWS,
    OPEN_ONLY,
}

internal enum class FoodWidgetVariant(
    val skin: SkinId,
    val presentation: WidgetPresentation,
    val receiverClassName: String,
    val widgetClassName: String,
) {
    SKY_MEADOW_SMALL(
        SkinId.SKY_MEADOW,
        WidgetPresentation.SMALL,
        "org.example.foodblob.widget.SkyMeadowSmallWidgetReceiver",
        "org.example.foodblob.widget.SkyMeadowSmallWidget",
    ),
    SHRINE_SMALL(
        SkinId.SHRINE,
        WidgetPresentation.SMALL,
        "org.example.foodblob.widget.ShrineSmallWidgetReceiver",
        "org.example.foodblob.widget.ShrineSmallWidget",
    ),
    SKY_MEADOW_MEDIUM(
        SkinId.SKY_MEADOW,
        WidgetPresentation.MEDIUM,
        "org.example.foodblob.widget.SkyMeadowMediumWidgetReceiver",
        "org.example.foodblob.widget.SkyMeadowMediumWidget",
    ),
    SHRINE_MEDIUM(
        SkinId.SHRINE,
        WidgetPresentation.MEDIUM,
        "org.example.foodblob.widget.ShrineMediumWidgetReceiver",
        "org.example.foodblob.widget.ShrineMediumWidget",
    ),
}

internal object FoodWidgetContract {
    const val MIN_TOUCH_TARGET_DP = 48f
    const val QUICK_WIDE_MIN_WIDTH_DP = 168f
    const val QUICK_WIDE_MIN_HEIGHT_DP = 48f
    const val QUICK_GRID_MIN_SIZE_DP = 108f
    const val QUICK_TALL_MIN_HEIGHT_DP = 212f
    const val QUICK_FEATURED_MIN_WIDTH_DP = 152f
    const val QUICK_FEATURED_MIN_HEIGHT_DP = 116f
    const val FULL_COLUMNS_MIN_WIDTH_DP = 250f
    const val FULL_COLUMNS_MIN_HEIGHT_DP = 108f
    const val FULL_ROWS_MIN_WIDTH_DP = 212f
    const val FULL_ROWS_MIN_HEIGHT_DP = 152f

    val colors = listOf(FoodColor.GREEN, FoodColor.YELLOW, FoodColor.RED)
    val dayRefreshActions = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.TIMEZONE_CHANGED",
        "android.intent.action.TIME_SET",
    )

    fun isSupportedDelta(delta: Int): Boolean = delta == -1 || delta == 1

    fun newEventId(): UUID = UUID.randomUUID()

    fun isActionEnabled(counts: FoodCounts, color: FoodColor, delta: Int): Boolean =
        isSupportedDelta(delta) && (delta > 0 || counts.count(color) > 0)
}

internal object WidgetDayRolloverContract {
    fun nextTriggerEpochMs(now: Instant, zoneId: ZoneId): Long =
        now.atZone(zoneId)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()
}

internal data class ResolvedWidgetGeometry(
    val widthDp: Float,
    val heightDp: Float,
    val layout: WidgetLayout,
    val outerPaddingDp: Float,
    val controlGapDp: Float,
    val rowGapDp: Float,
    val sectionGapDp: Float,
    val rowHeightDp: Float,
    val minusWidthDp: Float,
    val blobWidthDp: Float,
    val smallControlWidthDp: Float,
    val addWidthDp: Float,
    val blobRect: WidgetRect?,
    val actionTargets: List<WidgetActionTarget>,
)

internal data class WidgetRect(
    val leftDp: Float,
    val topDp: Float,
    val widthDp: Float,
    val heightDp: Float,
) {
    val rightDp: Float get() = leftDp + widthDp
    val bottomDp: Float get() = topDp + heightDp
}

internal data class WidgetActionTarget(
    val color: FoodColor,
    val delta: Int,
    val rect: WidgetRect,
)

internal object FoodWidgetGeometry {
    private const val CONTROL_GAP_DP = 4f
    private const val ROW_GAP_DP = 4f
    private const val SECTION_GAP_DP = 4f

    fun resolve(
        widthDp: Float,
        heightDp: Float,
        presentation: WidgetPresentation,
    ): ResolvedWidgetGeometry {
        val safeWidth = widthDp.coerceAtLeast(1f)
        val safeHeight = heightDp.coerceAtLeast(1f)
        val layout = when (presentation) {
            WidgetPresentation.SMALL -> quickLayout(safeWidth, safeHeight)
            WidgetPresentation.MEDIUM -> fullLayout(safeWidth, safeHeight)
        }
        val outerPadding = when (layout) {
            WidgetLayout.QUICK_WIDE -> 0f
            WidgetLayout.QUICK_TALL,
            WidgetLayout.QUICK_GRID,
            WidgetLayout.FULL_COLUMNS,
            WidgetLayout.OPEN_ONLY,
            -> 4f
            WidgetLayout.QUICK_FEATURED -> minOf(8f, (safeWidth - 152f) / 2f).coerceAtLeast(0f)
            WidgetLayout.FULL_ROWS -> minOf(12f, (safeHeight - 154f) / 2f).coerceAtLeast(0f)
        }
        val metrics = metrics(safeWidth, safeHeight, layout, outerPadding)

        return ResolvedWidgetGeometry(
            widthDp = safeWidth,
            heightDp = safeHeight,
            layout = layout,
            outerPaddingDp = outerPadding,
            controlGapDp = if (layout == WidgetLayout.FULL_ROWS) 8f else CONTROL_GAP_DP,
            rowGapDp = if (layout == WidgetLayout.FULL_ROWS) minOf(5f, (safeHeight - 144f) / 2f) else ROW_GAP_DP,
            sectionGapDp = if (layout == WidgetLayout.FULL_ROWS) 6f else SECTION_GAP_DP,
            rowHeightDp = FoodWidgetContract.MIN_TOUCH_TARGET_DP,
            minusWidthDp = metrics.minusWidthDp,
            blobWidthDp = metrics.blobRect?.widthDp ?: 0f,
            smallControlWidthDp = metrics.smallControlWidthDp,
            addWidthDp = metrics.addWidthDp,
            blobRect = metrics.blobRect,
            actionTargets = metrics.actionTargets,
        )
    }

    private fun quickLayout(widthDp: Float, heightDp: Float): WidgetLayout = when {
        widthDp >= FoodWidgetContract.QUICK_FEATURED_MIN_WIDTH_DP &&
            heightDp >= FoodWidgetContract.QUICK_FEATURED_MIN_HEIGHT_DP &&
            widthDp < heightDp * 2f -> WidgetLayout.QUICK_FEATURED
        widthDp >= FoodWidgetContract.QUICK_WIDE_MIN_WIDTH_DP &&
            heightDp >= FoodWidgetContract.QUICK_WIDE_MIN_HEIGHT_DP -> WidgetLayout.QUICK_WIDE
        widthDp >= FoodWidgetContract.QUICK_GRID_MIN_SIZE_DP &&
            heightDp >= FoodWidgetContract.QUICK_TALL_MIN_HEIGHT_DP -> WidgetLayout.QUICK_TALL
        widthDp >= FoodWidgetContract.QUICK_GRID_MIN_SIZE_DP &&
            heightDp >= FoodWidgetContract.QUICK_GRID_MIN_SIZE_DP -> WidgetLayout.QUICK_GRID
        else -> WidgetLayout.OPEN_ONLY
    }

    private fun fullLayout(widthDp: Float, heightDp: Float): WidgetLayout = when {
        widthDp >= FoodWidgetContract.FULL_ROWS_MIN_WIDTH_DP &&
            heightDp >= FoodWidgetContract.FULL_ROWS_MIN_HEIGHT_DP -> WidgetLayout.FULL_ROWS
        widthDp >= FoodWidgetContract.FULL_COLUMNS_MIN_WIDTH_DP &&
            heightDp >= FoodWidgetContract.FULL_COLUMNS_MIN_HEIGHT_DP -> WidgetLayout.FULL_COLUMNS
        else -> WidgetLayout.OPEN_ONLY
    }

    private data class LayoutMetrics(
        val blobRect: WidgetRect?,
        val actionTargets: List<WidgetActionTarget>,
        val smallControlWidthDp: Float = 0f,
        val addWidthDp: Float = 0f,
        val minusWidthDp: Float = 0f,
    )

    private fun metrics(
        widthDp: Float,
        heightDp: Float,
        layout: WidgetLayout,
        paddingDp: Float,
    ): LayoutMetrics = when (layout) {
        WidgetLayout.QUICK_WIDE -> {
            val controlWidth = (widthDp - 16f - CONTROL_GAP_DP * 2) / 3f
            val top = (heightDp - FoodWidgetContract.MIN_TOUCH_TARGET_DP) / 2f
            LayoutMetrics(
                blobRect = null,
                actionTargets = FoodWidgetContract.colors.mapIndexed { index, color ->
                    WidgetActionTarget(
                        color,
                        1,
                        WidgetRect(
                            8f + index * (controlWidth + CONTROL_GAP_DP),
                            top,
                            controlWidth,
                            FoodWidgetContract.MIN_TOUCH_TARGET_DP,
                        ),
                    )
                },
                smallControlWidthDp = controlWidth,
                addWidthDp = controlWidth,
            )
        }
        WidgetLayout.QUICK_FEATURED -> {
            val controlWidth = (widthDp - paddingDp * 2 - CONTROL_GAP_DP * 2) / 3f
            val controlTop = heightDp - paddingDp - FoodWidgetContract.MIN_TOUCH_TARGET_DP
            LayoutMetrics(
                blobRect = WidgetRect(
                    paddingDp,
                    paddingDp,
                    widthDp - paddingDp * 2,
                    controlTop - ROW_GAP_DP - paddingDp,
                ),
                actionTargets = FoodWidgetContract.colors.mapIndexed { index, color ->
                    WidgetActionTarget(
                        color,
                        1,
                        WidgetRect(
                            paddingDp + index * (controlWidth + CONTROL_GAP_DP),
                            controlTop,
                            controlWidth,
                            FoodWidgetContract.MIN_TOUCH_TARGET_DP,
                        ),
                    )
                },
                smallControlWidthDp = controlWidth,
                addWidthDp = controlWidth,
            )
        }
        WidgetLayout.QUICK_TALL -> {
            val controlTop = heightDp - paddingDp -
                FoodWidgetContract.MIN_TOUCH_TARGET_DP * 3 - ROW_GAP_DP * 2
            val controlWidth = widthDp - paddingDp * 2
            LayoutMetrics(
                blobRect = WidgetRect(
                    paddingDp,
                    paddingDp,
                    controlWidth,
                    controlTop - ROW_GAP_DP - paddingDp,
                ),
                actionTargets = FoodWidgetContract.colors.mapIndexed { index, color ->
                    WidgetActionTarget(
                        color,
                        1,
                        WidgetRect(
                            paddingDp,
                            controlTop + index * (FoodWidgetContract.MIN_TOUCH_TARGET_DP + ROW_GAP_DP),
                            controlWidth,
                            FoodWidgetContract.MIN_TOUCH_TARGET_DP,
                        ),
                    )
                },
                smallControlWidthDp = controlWidth,
                addWidthDp = controlWidth,
            )
        }
        WidgetLayout.QUICK_GRID -> {
            val cellWidth = (widthDp - paddingDp * 2 - CONTROL_GAP_DP) / 2f
            val cellHeight = (heightDp - paddingDp * 2 - ROW_GAP_DP) / 2f
            val cells = listOf(
                WidgetRect(paddingDp, paddingDp, cellWidth, cellHeight),
                WidgetRect(paddingDp + cellWidth + CONTROL_GAP_DP, paddingDp, cellWidth, cellHeight),
                WidgetRect(paddingDp, paddingDp + cellHeight + ROW_GAP_DP, cellWidth, cellHeight),
                WidgetRect(
                    paddingDp + cellWidth + CONTROL_GAP_DP,
                    paddingDp + cellHeight + ROW_GAP_DP,
                    cellWidth,
                    cellHeight,
                ),
            )
            LayoutMetrics(
                blobRect = cells.last(),
                actionTargets = FoodWidgetContract.colors.mapIndexed { index, color ->
                    WidgetActionTarget(color, 1, cells[index])
                },
                smallControlWidthDp = cellWidth,
                addWidthDp = cellWidth,
            )
        }
        WidgetLayout.FULL_COLUMNS -> {
            val blobWidth = 72f
            val controlLeft = paddingDp + blobWidth + SECTION_GAP_DP
            val controlWidth = (widthDp - controlLeft - paddingDp - CONTROL_GAP_DP * 2) / 3f
            val minusTop = paddingDp + FoodWidgetContract.MIN_TOUCH_TARGET_DP + ROW_GAP_DP
            LayoutMetrics(
                blobRect = WidgetRect(paddingDp, paddingDp, blobWidth, heightDp - paddingDp * 2),
                actionTargets = FoodWidgetContract.colors.flatMapIndexed { index, color ->
                    val left = controlLeft + index * (controlWidth + CONTROL_GAP_DP)
                    listOf(
                        WidgetActionTarget(
                            color,
                            1,
                            WidgetRect(left, paddingDp, controlWidth, FoodWidgetContract.MIN_TOUCH_TARGET_DP),
                        ),
                        WidgetActionTarget(
                            color,
                            -1,
                            WidgetRect(left, minusTop, controlWidth, FoodWidgetContract.MIN_TOUCH_TARGET_DP),
                        ),
                    )
                },
                addWidthDp = controlWidth,
                minusWidthDp = controlWidth,
            )
        }
        WidgetLayout.FULL_ROWS -> {
            // Match Apple's generous blob/row composition while retaining Android's
            // 48 dp touch zones, even at the launcher's smallest supported height.
            val sectionGap = 6f
            val controlGap = 8f
            val rowGap = minOf(5f, (heightDp - FoodWidgetContract.MIN_TOUCH_TARGET_DP * 3) / 2f)
            val rowsHeight = FoodWidgetContract.MIN_TOUCH_TARGET_DP * 3 + rowGap * 2
            val maxBlobWidth = widthDp - paddingDp * 2 - sectionGap - controlGap -
                FoodWidgetContract.MIN_TOUCH_TARGET_DP * 2
            val blobWidth = minOf((widthDp * 0.34f).coerceIn(72f, 142f), maxBlobWidth)
            val controlLeft = paddingDp + blobWidth + sectionGap
            val minusWidth = FoodWidgetContract.MIN_TOUCH_TARGET_DP
            val addWidth = widthDp - controlLeft - paddingDp - controlGap - minusWidth
            LayoutMetrics(
                blobRect = WidgetRect(paddingDp, paddingDp, blobWidth, heightDp - paddingDp * 2),
                actionTargets = FoodWidgetContract.colors.flatMapIndexed { index, color ->
                    val top = (heightDp - rowsHeight) / 2f + index * (FoodWidgetContract.MIN_TOUCH_TARGET_DP + rowGap)
                    listOf(
                        WidgetActionTarget(
                            color,
                            1,
                            WidgetRect(controlLeft, top, addWidth, FoodWidgetContract.MIN_TOUCH_TARGET_DP),
                        ),
                        WidgetActionTarget(
                            color,
                            -1,
                            WidgetRect(
                                controlLeft + addWidth + controlGap,
                                top,
                                minusWidth,
                                FoodWidgetContract.MIN_TOUCH_TARGET_DP,
                            ),
                        ),
                    )
                },
                addWidthDp = addWidth,
                minusWidthDp = minusWidth,
            )
        }
        WidgetLayout.OPEN_ONLY -> LayoutMetrics(
            blobRect = WidgetRect(
                paddingDp,
                paddingDp,
                (widthDp - paddingDp * 2).coerceAtLeast(0f),
                (heightDp - paddingDp * 2).coerceAtLeast(0f),
            ),
            actionTargets = emptyList(),
        )
    }
}
