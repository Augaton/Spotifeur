package fr.spotifeur.data.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Recent failures and account decisions, kept on the device so a user can attach them to a bug report.
 * Entries are written by Spotifeur itself: operation names, HTTP statuses, attribute values and audio
 * bitrates. They never contain tokens, usernames, URLs or content identifiers.
 */
object DiagnosticLog {
    private const val MAX_ENTRIES = 300
    private val entries = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "spotifeur-diagnostics").apply { isDaemon = true } }
    private val writeScheduled = AtomicBoolean()
    @Volatile private var file: File? = null
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(context: Context) {
        if (file != null) return
        val target = File(context.applicationContext.noBackupFilesDir, "diagnostics.log")
        synchronized(entries) {
            if (file != null) return
            val stored = runCatching { target.readLines() }.getOrDefault(emptyList())
            val merged = stored.takeLast(MAX_ENTRIES) + entries
            entries.clear()
            merged.takeLast(MAX_ENTRIES).forEach(entries::addLast)
            file = target
        }
        scheduleWrite()
    }

    fun record(area: String, message: String) {
        val line = "${synchronized(format) { format.format(Date()) }} $area ${message.replace('\n', ' ').take(300)}"
        runCatching { Log.i("SpotifeurDiagnostics", line) } // Not available in JVM unit tests.
        synchronized(entries) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        scheduleWrite()
    }

    fun lines(): List<String> = synchronized(entries) { entries.toList() }

    fun clear() {
        synchronized(entries) { entries.clear() }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        val target = file ?: return
        if (!writeScheduled.compareAndSet(false, true)) return
        writer.schedule({
            writeScheduled.set(false)
            runCatching { target.writeText(lines().joinToString("\n", postfix = "\n")) }
        }, 2, TimeUnit.SECONDS)
    }
}
