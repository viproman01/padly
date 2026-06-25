package app.padly.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("padly_settings")

class SettingsStore(private val context: Context) {
    object Keys {
        val cursorSensitivity = floatPreferencesKey("cursor_sensitivity")
        val naturalScroll = booleanPreferencesKey("natural_scroll")
        val haptic = booleanPreferencesKey("haptic")
        val hapticStrength = floatPreferencesKey("haptic_strength")
        val mode = stringPreferencesKey("mode") // wifi | bluetooth
    }

    val cursorSensitivity: Flow<Float> = context.dataStore.data.map { it[Keys.cursorSensitivity] ?: 1.0f }
    val naturalScroll: Flow<Boolean> = context.dataStore.data.map { it[Keys.naturalScroll] ?: true }
    val hapticEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.haptic] ?: true }
    val hapticStrength: Flow<Float> = context.dataStore.data.map { it[Keys.hapticStrength] ?: 0.6f }
    val mode: Flow<String> = context.dataStore.data.map { it[Keys.mode] ?: "wifi" }

    suspend fun setCursorSensitivity(v: Float) {
        context.dataStore.edit { it[Keys.cursorSensitivity] = v }
    }
    suspend fun setNaturalScroll(v: Boolean) {
        context.dataStore.edit { it[Keys.naturalScroll] = v }
    }
    suspend fun setHapticEnabled(v: Boolean) {
        context.dataStore.edit { it[Keys.haptic] = v }
    }
    suspend fun setHapticStrength(v: Float) {
        context.dataStore.edit { it[Keys.hapticStrength] = v }
    }
    suspend fun setMode(v: String) {
        context.dataStore.edit { it[Keys.mode] = v }
    }
}
