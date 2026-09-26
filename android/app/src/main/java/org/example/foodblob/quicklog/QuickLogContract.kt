package org.example.foodblob.quicklog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import java.util.UUID

internal data class QuickLogShortcutDefinition(
    val id: String,
    val color: FoodColor,
    @param:StringRes val shortLabel: Int,
    @param:StringRes val longLabel: Int,
    @param:DrawableRes val icon: Int,
)

internal object QuickLogContract {
    const val ACTION_LOG = "org.example.foodblob.action.QUICK_LOG"
    const val EXTRA_COLOR = "org.example.foodblob.extra.QUICK_LOG_COLOR"
    const val STATE_EVENT_ID = "quick_log_event_id"
    const val STATE_CONFIRMED = "quick_log_confirmed"

    val definitions = listOf(
        QuickLogShortcutDefinition(
            id = "log_green",
            color = FoodColor.GREEN,
            shortLabel = R.string.shortcut_add_green,
            longLabel = R.string.shortcut_long_add_green,
            icon = R.drawable.ic_shortcut_green,
        ),
        QuickLogShortcutDefinition(
            id = "log_yellow",
            color = FoodColor.YELLOW,
            shortLabel = R.string.shortcut_add_yellow,
            longLabel = R.string.shortcut_long_add_yellow,
            icon = R.drawable.ic_shortcut_yellow,
        ),
        QuickLogShortcutDefinition(
            id = "log_red",
            color = FoodColor.RED,
            shortLabel = R.string.shortcut_add_red,
            longLabel = R.string.shortcut_long_add_red,
            icon = R.drawable.ic_shortcut_red,
        ),
    )

    fun colorForIntent(action: String?, storageId: String?): FoodColor? {
        if (action != ACTION_LOG) return null
        return storageId?.let(FoodColor::fromStorage)
    }

    fun resolveEventId(saved: String?, generate: () -> UUID = UUID::randomUUID): UUID =
        saved?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: generate()
}
