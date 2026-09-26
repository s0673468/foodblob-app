package org.example.foodblob

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import org.example.foodblob.ui.FoodBlobApp
import org.example.foodblob.ui.FoodBlobStartupFailure
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openTodayRequests = MutableStateFlow(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeDeepLink(intent)
        val foodBlobApplication = application as FoodBlobApplication
        splash.setKeepOnScreenCondition { !foodBlobApplication.startupSucceeded.isCompleted }
        lifecycleScope.launch {
            if (foodBlobApplication.startupSucceeded.await()) {
                setContent {
                    FoodBlobApp(
                        openTodayRequests = openTodayRequests,
                        onFirstUsableFrame = ::reportFullyDrawn,
                    )
                }
            } else {
                setContent { FoodBlobStartupFailure() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeDeepLink(intent)
    }

    private fun consumeDeepLink(intent: Intent?) {
        if (isTodayDeepLink(intent?.dataString)) {
            openTodayRequests.value += 1
        }
    }
}

internal fun isTodayDeepLink(rawUri: String?): Boolean =
    rawUri == "foodblob://today" || rawUri == "foodblob:///today"
