package fr.spotifeur.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class PlaybackCommandsTest {
    @Test fun retryAfterErrorStartsOnFirstPress() {
        val calls = mutableListOf<String>()
        val error = PlaybackException("network", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        togglePlayback(player(calls, error, true, Player.STATE_IDLE))
        assertEquals(listOf("prepare", "play"), calls)
    }

    @Test fun playingWithoutErrorPauses() {
        val calls = mutableListOf<String>()
        togglePlayback(player(calls, null, true, Player.STATE_READY))
        assertEquals(listOf("pause"), calls)
    }

    private fun player(calls: MutableList<String>, error: PlaybackException?, ready: Boolean, state: Int): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ ->
            when (method.name) {
                "getPlayerError" -> error
                "getPlayWhenReady" -> ready
                "getPlaybackState" -> state
                "prepare", "play", "pause" -> { calls += method.name; null }
                else -> throw AssertionError("Unexpected command: ${method.name}")
            }
        } as Player
}
