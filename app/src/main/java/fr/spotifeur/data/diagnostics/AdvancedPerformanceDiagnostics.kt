package fr.spotifeur.data.diagnostics

/** Diagnostics hooks, disabled: Spotifeur records no performance trace. */
object AdvancedPerformanceDiagnostics {
    const val isEnabled = false
    object EventTypes { const val AUDIO_EFFECT = "audio_effect"; const val UI = "ui" }
    suspend fun <T> traceSuspend(@Suppress("UNUSED_PARAMETER") name: String, block: suspend () -> T): T = block()
    fun recordEvent(type: String, name: String, details: Map<String, String> = emptyMap()) = Unit
    inline fun recordEventIfEnabled(type: String, name: String, elapsedRealtimeMs: Long = 0L, details: () -> Map<String, String> = { emptyMap() }) = Unit
}
