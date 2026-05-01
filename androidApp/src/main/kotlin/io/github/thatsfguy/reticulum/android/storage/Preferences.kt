package io.github.thatsfguy.reticulum.android.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Tiny SharedPreferences wrapper for user-tunable settings that don't
 * belong in the protocol-state Room database. Right now: just the
 * display name we advertise in our announce app_data.
 *
 * Read access is synchronous (Engine reads it once per announce). For
 * the UI we expose a Flow so the Settings screen reacts when the user
 * saves a new value.
 */
class Preferences(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    private val _displayName = MutableStateFlow(prefs.getString(KEY_DISPLAY_NAME, DEFAULT_DISPLAY_NAME) ?: DEFAULT_DISPLAY_NAME)
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    fun getDisplayName(): String = _displayName.value

    fun setDisplayName(value: String) {
        val normalized = value.trim().ifEmpty { DEFAULT_DISPLAY_NAME }
        prefs.edit().putString(KEY_DISPLAY_NAME, normalized).apply()
        _displayName.value = normalized
    }

    companion object {
        private const val NAME = "reticulum_prefs"
        private const val KEY_DISPLAY_NAME = "display_name"
        const val DEFAULT_DISPLAY_NAME = "Reticulum Mobile"
    }
}
