package org.example.foodblob.quicklog

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import org.example.foodblob.R

internal object FoodBlobQuickShortcuts {
    fun publish(context: Context) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = QuickLogContract.definitions.mapIndexed { rank, definition ->
            ShortcutInfo.Builder(context, definition.id)
                .setShortLabel(context.getString(definition.shortLabel))
                .setLongLabel(context.getString(definition.longLabel))
                .setDisabledMessage(context.getString(R.string.quick_log_shortcut_unavailable))
                .setIcon(Icon.createWithResource(context, definition.icon))
                .setIntent(
                    Intent(context, QuickLogActivity::class.java)
                        .setAction(QuickLogContract.ACTION_LOG)
                        .putExtra(QuickLogContract.EXTRA_COLOR, definition.color.storageId),
                )
                .setRank(rank)
                .build()
        }
        shortcutManager.dynamicShortcuts = shortcuts
    }
}
