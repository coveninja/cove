package com.coveninja.cove.ui.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberScreenBrightness(): ScreenBrightnessController {
    val activity = LocalContext.current.findActivity()
    val controller = remember(activity) {
        if (activity == null) InertScreenBrightness else WindowScreenBrightness(activity)
    }
    // A window override outlives the composition that set it, so leaving the player without
    // this would keep the screen at whatever the last swipe chose for the rest of the session.
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * Brightness as a window attribute.
 *
 * The starting point is deliberately the *current system* brightness rather than 1.0 or the
 * midpoint: the first upward swipe should raise the screen from where the viewer already had
 * it, not jump it somewhere and then adjust. Android spells "follow the system" as the
 * sentinel -1, so the first adjustment has to resolve that into a real number before it can
 * move relative to it.
 */
private class WindowScreenBrightness(private val activity: Activity) : ScreenBrightnessController {
    override val supported: Boolean = true

    private var current by mutableStateOf(
        activity.window.attributes.screenBrightness.takeIf { it >= 0f },
    )

    override val level: Float? get() = current

    override fun adjust(delta: Float) {
        val from = current ?: systemBrightness()
        // Never fully dark: a screen at zero looks like a player that crashed, and the gesture
        // to bring it back is invisible on it.
        val next = (from + delta).coerceIn(MINIMUM, 1f)
        current = next
        apply(next)
    }

    override fun release() {
        current = null
        apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun apply(value: Float) {
        runCatching {
            activity.window.attributes = activity.window.attributes.apply {
                screenBrightness = value
            }
        }
    }

    /**
     * The system brightness, 0..1, or a middling default when it cannot be read.
     *
     * `Settings.System.SCREEN_BRIGHTNESS` is on the 0..255 scale and is readable without
     * permission; only *writing* it needs one, which is why this reads the system value and
     * writes the window.
     */
    private fun systemBrightness(): Float = runCatching {
        val raw = android.provider.Settings.System.getInt(
            activity.contentResolver,
            android.provider.Settings.System.SCREEN_BRIGHTNESS,
        )
        (raw / 255f).coerceIn(MINIMUM, 1f)
    }.getOrDefault(0.5f)

    private companion object {
        const val MINIMUM = 0.02f
    }
}

private object InertScreenBrightness : ScreenBrightnessController {
    override val supported: Boolean = false
    override val level: Float? = null
    override fun adjust(delta: Float) = Unit
    override fun release() = Unit
}

/**
 * The Activity behind a Compose context, which is not always the context itself — Compose can
 * be handed a themed wrapper, and the window only exists on the activity underneath it.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
