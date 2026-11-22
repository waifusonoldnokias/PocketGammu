package com.pocketgammu.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pocketgammu.core.model.ColorPalette
import com.pocketgammu.core.model.SessionDefaults
import com.pocketgammu.core.model.ThemeMode
import com.pocketgammu.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocketgammu_settings")

/** Single source of truth for user preferences, backed by Preferences DataStore. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val palette = stringPreferencesKey("palette")
        val haptics = booleanPreferencesKey("haptics")
        val connectionType = stringPreferencesKey("connection_type")
        val phoneFamily = stringPreferencesKey("phone_family")
        val powerCable = booleanPreferencesKey("power_cable")
        val networkCode = stringPreferencesKey("network_code")
        val recipient = stringPreferencesKey("recipient")
    }

    val settings: Flow<UserSettings> = store.data.map { p ->
        UserSettings(
            themeMode = p[Keys.themeMode].toEnum(ThemeMode.SYSTEM),
            palette = p[Keys.palette].toEnum(ColorPalette.SYSTEM),
            hapticsEnabled = p[Keys.haptics] ?: true,
        )
    }.distinctUntilChanged()

    val sessionDefaults: Flow<SessionDefaults> = store.data.map { p ->
        SessionDefaults(
            connectionTypeId = p[Keys.connectionType],
            phoneFamilyId = p[Keys.phoneFamily],
            powerCable = p[Keys.powerCable] ?: true,
            networkCode = p[Keys.networkCode] ?: "",
            recipient = p[Keys.recipient] ?: "",
        )
    }.distinctUntilChanged()

    suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.themeMode] = mode.name } }
    suspend fun setPalette(palette: ColorPalette) { store.edit { it[Keys.palette] = palette.name } }
    suspend fun setHaptics(enabled: Boolean) { store.edit { it[Keys.haptics] = enabled } }

    suspend fun setConnection(typeId: String, familyId: String, powerCable: Boolean) {
        store.edit {
            it[Keys.connectionType] = typeId
            it[Keys.phoneFamily] = familyId
            it[Keys.powerCable] = powerCable
        }
    }

    suspend fun setNetworkCode(code: String) { store.edit { it[Keys.networkCode] = code } }
    suspend fun setRecipient(number: String) { store.edit { it[Keys.recipient] = number } }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
