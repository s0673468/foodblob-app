package org.example.foodblob

import android.app.Application
import org.example.foodblob.quicklog.FoodBlobQuickShortcuts
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.WidgetUpdateBridge
import org.example.foodblob.widget.FoodBlobWidgetUpdater
import org.example.foodblob.widget.WidgetDayRolloverScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WIDGET_REFRESH_MAX_ATTEMPTS = 3
private const val WIDGET_REFRESH_RETRY_DELAY_MS = 250L

class FoodBlobApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val widgetUpdateRequests = Channel<Unit>(Channel.CONFLATED)
    internal val startupSucceeded = CompletableDeferred<Boolean>()

    override fun onCreate() {
        super.onCreate()
        try {
            FoodBlobQuickShortcuts.publish(this)
        } catch (_: RuntimeException) {
            // Shortcut publication is optional and must not prevent the private store from opening.
        }
        WidgetUpdateBridge.updater = {
            widgetUpdateRequests.trySend(Unit)
            Unit
        }
        WidgetDayRolloverScheduler.schedule(this)
        applicationScope.launch {
            consumeWidgetUpdateRequests(widgetUpdateRequests) {
                FoodBlobWidgetUpdater.updateAll(this@FoodBlobApplication)
            }
        }
        applicationScope.launch {
            completeStartup(startupSucceeded) {
                FoodBlobServices.get(this@FoodBlobApplication).store.apply {
                    ensureInitialized()
                    reconcilePending()
                }
            }
        }
    }
}

internal suspend fun consumeWidgetUpdateRequests(
    requests: ReceiveChannel<Unit>,
    update: suspend () -> Unit,
) {
    for (ignored in requests) {
        var attempt = 0
        while (attempt < WIDGET_REFRESH_MAX_ATTEMPTS) {
            attempt += 1
            try {
                update()
                break
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Widget hosts are external process state. Retry transient failures
                // without terminating the process-wide consumer or spinning forever.
                if (attempt < WIDGET_REFRESH_MAX_ATTEMPTS) {
                    delay(WIDGET_REFRESH_RETRY_DELAY_MS * attempt)
                }
            }
        }
    }
}

internal suspend fun completeStartup(
    gate: CompletableDeferred<Boolean>,
    initialize: suspend () -> Unit,
) {
    try {
        initialize()
        gate.complete(true)
    } catch (error: CancellationException) {
        gate.complete(false)
        throw error
    } catch (_: Exception) {
        gate.complete(false)
    }
}
