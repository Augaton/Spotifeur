package fr.spotifeur.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import fr.spotifeur.data.equalizer.EqualizerManager
import fr.spotifeur.data.preferences.EqualizerPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal val Context.equalizerDataStore: DataStore<Preferences> by preferencesDataStore(name = "equalizer")

/** Links PixelPlayer's equalizer to the audio session of the playback service. */
object EqualizerRuntime {
    val manager by lazy { EqualizerManager() }
    private val session = MutableStateFlow(0)
    val activeAudioSessionId: StateFlow<Int> = session.asStateFlow()
    fun getAudioSessionId(): Int = session.value
    fun setAudioSessionId(id: Int) { session.value = id }

    fun repository(context: Context) = EqualizerPreferencesRepository(
        context.applicationContext.equalizerDataStore, Json { ignoreUnknownKeys = true },
    )

    /** Applies the saved effects as soon as audio exists, without opening the equalizer screen. */
    fun start(context: Context, scope: CoroutineScope) {
        val preferences = repository(context)
        scope.launch {
            manager.restoreState(
                preferences.equalizerEnabledFlow.first(), preferences.equalizerPresetFlow.first(),
                preferences.equalizerCustomBandsFlow.first(), preferences.bassBoostEnabledFlow.first(),
                preferences.bassBoostStrengthFlow.first(), preferences.virtualizerEnabledFlow.first(),
                preferences.virtualizerStrengthFlow.first(), preferences.loudnessEnhancerEnabledFlow.first(),
                preferences.loudnessEnhancerStrengthFlow.first(),
            )
            session.collectLatest { id -> if (id != 0) manager.attachToAudioSessionIfNeeded(id) }
        }
    }
}
