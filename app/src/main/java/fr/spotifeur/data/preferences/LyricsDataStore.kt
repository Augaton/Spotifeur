package fr.spotifeur.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Display options of the lyrics screen. */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyrics_display")
