package org.example.foodblob.quicklog

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import org.example.foodblob.FoodBlobApplication
import org.example.foodblob.R
import org.example.foodblob.domain.FoodColor
import org.example.foodblob.storage.FoodBlobServices
import org.example.foodblob.storage.IdempotentMutationResult
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class QuickLogActivity : ComponentActivity() {
    private lateinit var eventId: UUID
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var done: Button
    private lateinit var colorMark: TextView
    private var confirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        eventId = QuickLogContract.resolveEventId(
            savedInstanceState?.getString(QuickLogContract.STATE_EVENT_ID),
        )
        confirmed = savedInstanceState?.getBoolean(QuickLogContract.STATE_CONFIRMED) == true
        val color = QuickLogContract.colorForIntent(
            intent?.action,
            intent?.getStringExtra(QuickLogContract.EXTRA_COLOR),
        )
        setContentView(buildConfirmationSurface(color))
        if (color == null) {
            showFailure()
        } else if (confirmed) {
            showSaving()
            saveOffering(color)
        } else {
            showConfirmation(color)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(QuickLogContract.STATE_EVENT_ID, eventId.toString())
        outState.putBoolean(QuickLogContract.STATE_CONFIRMED, confirmed)
        super.onSaveInstanceState(outState)
    }

    private fun confirmQuickLog(color: FoodColor) {
        if (confirmed) return
        confirmed = true
        showSaving()
        saveOffering(color)
    }

    private fun saveOffering(color: FoodColor) {
        val userManager = getSystemService(UserManager::class.java)
        if (userManager?.isUserUnlocked != true) {
            showLocked()
            return
        }
        lifecycleScope.launch {
            try {
                val application = application as? FoodBlobApplication
                    ?: throw IllegalStateException("Food Blob application is unavailable")
                if (!application.startupSucceeded.await()) {
                    showFailure()
                    return@launch
                }
                when (FoodBlobServices.get(application).store.appendQuickLogAction(color, eventId)) {
                    IdempotentMutationResult.COMMITTED -> {
                        window.decorView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        showSuccess(color, wasAlreadySaved = false)
                    }
                    IdempotentMutationResult.ALREADY_COMMITTED -> {
                        showSuccess(color, wasAlreadySaved = true)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showFailure()
            }
        }
    }

    private fun buildConfirmationSurface(color: FoodColor?): View {
        val density = resources.displayMetrics.density
        val horizontalInset = (24 * density).toInt()
        val verticalInset = (32 * density).toInt()
        val screenWidth = resources.configuration.screenWidthDp
        val surfaceWidth = (minOf(screenWidth - 48, 480).coerceAtLeast(1) * density).toInt()

        val root = FrameLayout(this).apply {
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(resolveThemeColor(android.R.attr.colorBackground))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val scroll = ScrollView(this).apply {
            id = R.id.quick_log_scroll
            isFillViewport = true
            isFocusable = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        root.addView(
            scroll,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(horizontalInset, verticalInset, horizontalInset, verticalInset)
        }
        scroll.addView(
            content,
            FrameLayout.LayoutParams(
                surfaceWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL,
            ),
        )

        colorMark = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 34f
            setTextColor(0xffffffff.toInt())
            setTypeface(typeface, Typeface.BOLD)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = circle(color?.displayColor() ?: 0xff777777.toInt())
        }
        content.addView(colorMark, LinearLayout.LayoutParams((72 * density).toInt(), (72 * density).toInt()))

        title = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, (24 * density).toInt(), 0, 0)
            text = getString(R.string.quick_log_saving)
        }
        content.addView(title, matchWidthWrapHeight())

        detail = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(0, (12 * density).toInt(), 0, 0)
            text = getString(R.string.quick_log_saving_detail)
        }
        content.addView(detail, matchWidthWrapHeight())

        progress = ProgressBar(this).apply {
            isIndeterminate = true
            contentDescription = getString(R.string.quick_log_saving)
        }
        content.addView(
            progress,
            LinearLayout.LayoutParams((48 * density).toInt(), (64 * density).toInt()).apply {
                topMargin = (12 * density).toInt()
            },
        )

        done = Button(this).apply {
            id = R.id.quick_log_primary_action
            text = getString(R.string.quick_log_done)
            minHeight = (48 * density).toInt()
            visibility = View.GONE
        }
        content.addView(
            done,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (20 * density).toInt()
            },
        )
        return root
    }

    private fun showConfirmation(color: FoodColor) {
        progress.visibility = View.GONE
        title.setText(R.string.quick_log_confirm_title)
        detail.text = getString(R.string.quick_log_confirm_detail, getString(color.label()))
        done.apply {
            setText(color.confirmLabel())
            isEnabled = true
            visibility = View.VISIBLE
            setOnClickListener { confirmQuickLog(color) }
        }
    }

    private fun showSaving() {
        title.setText(R.string.quick_log_saving)
        detail.setText(R.string.quick_log_saving_detail)
        progress.visibility = View.VISIBLE
        done.apply {
            isEnabled = false
            visibility = View.GONE
            setOnClickListener(null)
        }
    }

    private fun showSuccess(color: FoodColor, wasAlreadySaved: Boolean) {
        progress.visibility = View.GONE
        done.apply {
            setText(R.string.quick_log_done)
            isEnabled = true
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        title.setText(color.successTitle())
        detail.setText(
            if (wasAlreadySaved) R.string.quick_log_already_saved else R.string.quick_log_success_detail,
        )
    }

    private fun showFailure() {
        progress.visibility = View.GONE
        done.apply {
            setText(R.string.quick_log_done)
            isEnabled = true
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        colorMark.background = circle(0xff777777.toInt())
        title.setText(R.string.quick_log_failed_title)
        detail.setText(R.string.quick_log_failed_detail)
    }

    private fun showLocked() {
        progress.visibility = View.GONE
        done.apply {
            setText(R.string.quick_log_done)
            isEnabled = true
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
        colorMark.background = circle(0xff777777.toInt())
        title.setText(R.string.quick_log_locked_title)
        detail.setText(R.string.quick_log_locked_detail)
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun resolveThemeColor(attribute: Int): Int {
        val value = android.util.TypedValue()
        theme.resolveAttribute(attribute, value, true)
        return value.data
    }
}

private fun FoodColor.displayColor(): Int = when (this) {
    FoodColor.GREEN -> 0xff2ec77a.toInt()
    FoodColor.YELLOW -> 0xffe7b92c.toInt()
    FoodColor.RED -> 0xffe15b5b.toInt()
}

private fun FoodColor.successTitle(): Int = when (this) {
    FoodColor.GREEN -> R.string.quick_log_success_green
    FoodColor.YELLOW -> R.string.quick_log_success_yellow
    FoodColor.RED -> R.string.quick_log_success_red
}

private fun FoodColor.label(): Int = when (this) {
    FoodColor.GREEN -> R.string.green
    FoodColor.YELLOW -> R.string.yellow
    FoodColor.RED -> R.string.red
}

private fun FoodColor.confirmLabel(): Int = when (this) {
    FoodColor.GREEN -> R.string.quick_log_confirm_green
    FoodColor.YELLOW -> R.string.quick_log_confirm_yellow
    FoodColor.RED -> R.string.quick_log_confirm_red
}
