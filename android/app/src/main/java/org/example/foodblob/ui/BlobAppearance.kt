package org.example.foodblob.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.roundToInt

/** Local presentation only. This key is deliberately outside the food snapshot/export. */
internal object BlobAppearance {
    const val KEY = "blob_translucency"
    const val PREFERENCES = "food_blob_ui"

    fun normalize(value: Float): Float =
        if (value.isFinite()) (value.coerceIn(0f, 1f) * 20f).roundToInt() / 20f else 0f

    fun read(preferences: SharedPreferences): Float =
        normalize((preferences.all[KEY] as? Float) ?: 0f)

    fun save(preferences: SharedPreferences, value: Float) {
        preferences.edit().putFloat(KEY, normalize(value)).apply()
    }
}

internal val LocalBlobTranslucency = staticCompositionLocalOf { 0f }

/** Also usable by Glance: no dependence on UI-only composition locals. */
@Composable
internal fun rememberBlobTranslucency(context: Context): State<Float> {
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(BlobAppearance.PREFERENCES, Context.MODE_PRIVATE)
    }
    val value = remember(preferences) { mutableFloatStateOf(BlobAppearance.read(preferences)) }
    DisposableEffect(preferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == BlobAppearance.KEY || key == null) value.floatValue = BlobAppearance.read(preferences)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        value.floatValue = BlobAppearance.read(preferences)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
