package app.weave

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf

/**
 * A device-local accessibility preference: 100% is the compact size published profiles
 * currently use. Never modify authored Element.fontSize, profile storage, or daemon data.
 * Keep in ordinary Android preferences so it works before a VeilKnit account is opened.
 */
internal const val PROFILE_TEXT_DEFAULT_MULTIPLIER = 1f
internal const val PROFILE_TEXT_MAX_MULTIPLIER = 3f

internal fun clampProfileTextMultiplier(value: Float): Float =
    if (value.isFinite()) value.coerceIn(PROFILE_TEXT_DEFAULT_MULTIPLIER, PROFILE_TEXT_MAX_MULTIPLIER)
    else PROFILE_TEXT_DEFAULT_MULTIPLIER

internal val LocalProfileTextSizeMultiplier = compositionLocalOf { PROFILE_TEXT_DEFAULT_MULTIPLIER }

class ProfileTextSizeSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "weave_accessibility", Context.MODE_PRIVATE
    )

    var multiplier by mutableFloatStateOf(
        clampProfileTextMultiplier(prefs.getFloat("profile_text_multiplier", PROFILE_TEXT_DEFAULT_MULTIPLIER))
    )
        private set

    fun updateMultiplier(value: Float) {
        val newValue = clampProfileTextMultiplier(value)
        if (newValue == multiplier) return
        multiplier = newValue
        prefs.edit().putFloat("profile_text_multiplier", newValue).apply()
    }
}
