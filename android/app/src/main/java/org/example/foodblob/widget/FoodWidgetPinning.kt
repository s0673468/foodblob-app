package org.example.foodblob.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

internal enum class PinRequestResult {
    REQUEST_SENT,
    UNSUPPORTED,
    FAILED,
}

internal object FoodWidgetPinning {
    fun receiverClass(variant: FoodWidgetVariant): Class<out FoodBlobWidgetReceiver> =
        when (variant) {
            FoodWidgetVariant.SKY_MEADOW_SMALL -> SkyMeadowSmallWidgetReceiver::class.java
            FoodWidgetVariant.SHRINE_SMALL -> ShrineSmallWidgetReceiver::class.java
            FoodWidgetVariant.SKY_MEADOW_MEDIUM -> SkyMeadowMediumWidgetReceiver::class.java
            FoodWidgetVariant.SHRINE_MEDIUM -> ShrineMediumWidgetReceiver::class.java
        }

    fun isSupported(context: Context): Boolean =
        AppWidgetManager.getInstance(context.applicationContext).isRequestPinAppWidgetSupported

    fun request(context: Context, variant: FoodWidgetVariant): PinRequestResult {
        val applicationContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(applicationContext)
        if (!manager.isRequestPinAppWidgetSupported) return PinRequestResult.UNSUPPORTED

        return try {
            val receiver = ComponentName(applicationContext, receiverClass(variant))
            if (manager.requestPinAppWidget(receiver, null, null)) {
                PinRequestResult.REQUEST_SENT
            } else {
                PinRequestResult.UNSUPPORTED
            }
        } catch (_: RuntimeException) {
            PinRequestResult.FAILED
        }
    }
}
