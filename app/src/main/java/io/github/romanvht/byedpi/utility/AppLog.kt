package io.github.romanvht.byedpi.utility

import android.os.Process
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app ring buffer log: everything written here is also forwarded to
 * logcat, but kept in memory so it can be shown and copied from the
 * built-in logs screen without adb.
 */
object AppLog {
    private const val MAX_LINES = 800

    /**
     * Storage cap for on-disk log files: only the most recent records within
     * this budget are kept.
     */
    const val LOG_FILE_MAX_BYTES = 500_000

    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /**
     * Keeps only the trailing [AppLog.LOG_FILE_MAX_BYTES] bytes of the file.
     * Safe to call only when no process is appending to it.
     */
    fun trimFileTail(file: File, maxBytes: Long = LOG_FILE_MAX_BYTES.toLong()) {
        try {
            if (!file.exists() || file.length() <= maxBytes) return

            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - maxBytes)
                val tail = ByteArray(maxBytes.toInt())
                raf.readFully(tail)
                // do not start mid-line: drop everything before the first newline
                val start = tail.indexOf('\n'.code.toByte()) + 1
                if (start <= 0) return

                raf.setLength(0)
                raf.seek(0)
                raf.write(tail, start, tail.size - start)
            }
        } catch (e: Exception) {
            Log.w("AppLog", "log trim failed: ${e.message}")
        }
    }

    private fun ByteArray.indexOf(byte: Byte): Int =
        indexOfFirst { it == byte }

    @JvmStatic
    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        add("I/$tag: $msg")
    }

    @JvmStatic
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        add("W/$tag: $msg")
    }

    @JvmStatic
    @JvmOverloads
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        add("E/$tag: $msg${tr?.let { " [${it.javaClass.simpleName}]" } ?: ""}")
    }

    @Synchronized
    private fun add(line: String) {
        lines.addLast("${format.format(Date())} $line")
        while (lines.size > MAX_LINES) {
            lines.removeFirst()
        }
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "no in-app records yet" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()

    /**
     * Dump of the system logcat for this application process, which also
     * captures the native ciadpi messages (tag "proxy").
     */
    fun dumpSystemLogcat(maxLines: Int = 800): String = try {
        val process = ProcessBuilder(
            "logcat", "-d", "-v", "time", "-t", maxLines.toString(),
            "--pid", Process.myPid().toString(),
        ).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        text.ifBlank { "logcat output is empty" }
    } catch (e: Exception) {
        "logcat unavailable: ${e.message}"
    }
}
