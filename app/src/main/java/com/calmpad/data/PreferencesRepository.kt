package com.calmpad.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "calmpad_prefs")

@Singleton
class PreferencesRepository @Inject constructor(private val context: Context) {

    companion object {
        private val THEME_KEY = stringPreferencesKey("theme")
        private val FONT_KEY = stringPreferencesKey("font")
        private val ACTIVE_NOTE_KEY = stringPreferencesKey("active_note_id")
        private val ACTIVE_SECTION_KEY = stringPreferencesKey("active_section_id")
    }

    val theme: Flow<String> = context.dataStore.data.map { it[THEME_KEY] ?: "light" }
    val font: Flow<String> = context.dataStore.data.map { it[FONT_KEY] ?: "sans" }
    val activeNoteId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_NOTE_KEY] }
    val activeSectionId: Flow<String> = context.dataStore.data.map { it[ACTIVE_SECTION_KEY] ?: "default" }

    suspend fun setTheme(value: String) {
        context.dataStore.edit { it[THEME_KEY] = value }
    }

    suspend fun setFont(value: String) {
        context.dataStore.edit { it[FONT_KEY] = value }
    }

    suspend fun setActiveNoteId(value: String?) {
        context.dataStore.edit {
            if (value != null) it[ACTIVE_NOTE_KEY] = value
            else it.remove(ACTIVE_NOTE_KEY)
        }
    }

    suspend fun setActiveSectionId(value: String) {
        context.dataStore.edit { it[ACTIVE_SECTION_KEY] = value }
    }
}
