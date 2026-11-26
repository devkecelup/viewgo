package dev.viewgo

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory log so a phone without USB debugging can show what happened, and stream it to the
 * laptop once the encrypted session is up.
 */
object AppLog {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    private var sink: ((String) -> Unit)? = null

    fun line(message: String) {
        val text = "${stamp.format(Date())} $message"
        synchronized(lines) {
            lines.addLast(text)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        sink?.invoke(text)
    }

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun attach(sink: ((String) -> Unit)?) {
        this.sink = sink
    }
}
