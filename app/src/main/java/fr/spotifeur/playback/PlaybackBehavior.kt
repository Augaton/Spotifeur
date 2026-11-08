package fr.spotifeur.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.Player
import fr.spotifeur.data.preferences.SettingsStore

/** The playback rules of the settings that depend on the audio output. */
internal class PlaybackBehavior(context: Context, private val player: Player) : AutoCloseable {
    private val settings = SettingsStore.get(context)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var pausedByNoisyOutput = false
    private val listener = object : Player.Listener {
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) {
            if ((volume == 0 || muted) && settings.settings.value.pauseOnVolumeZero && player.isPlaying) player.pause()
        }
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pausedByNoisyOutput = !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
        }
    }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (pausedByNoisyOutput && settings.settings.value.resumeOnHeadphones && addedDevices.any { it.isSink && it.type in HEADPHONES }) {
                pausedByNoisyOutput = false
                player.play()
            }
        }
    }
    fun start() {
        player.addListener(listener)
        audio.registerAudioDeviceCallback(devices, android.os.Handler(player.applicationLooper))
    }
    override fun close() {
        player.removeListener(listener)
        audio.unregisterAudioDeviceCallback(devices)
    }
    private companion object {
        val HEADPHONES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
