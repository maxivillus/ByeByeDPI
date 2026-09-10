package io.github.romanvht.byedpi.activities

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.utility.AppLog
import io.github.romanvht.byedpi.utility.ClipboardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : BaseActivity() {

    private lateinit var logsText: TextView

    private val createLogDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) saveToFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logsText = findViewById(R.id.logs_text)

        findViewById<Button>(R.id.logs_refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.logs_copy).setOnClickListener {
            ClipboardUtils.copy(this, logsText.text.toString(), "app logs")
        }
        findViewById<Button>(R.id.logs_dump).setOnClickListener { refresh(withSystemLog = true) }
        findViewById<Button>(R.id.logs_save).setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            createLogDocument.launch("byebyedpi-log-$stamp.txt")
        }
        findViewById<Button>(R.id.logs_clear).setOnClickListener {
            AppLog.clear()
            runCatching { java.io.File(cacheDir, "hev.log").delete() }
            logsText.text = getString(R.string.logs_cleared)
        }

        // Heavy text assembly runs off the main thread; the giant
        // TextView is filled only after it is ready
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh(withSystemLog: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            val text = buildString {
                appendLine(AppLog.dump())
                if (withSystemLog) {
                    appendLine()
                    appendLine("=== logcat (this process) ===")
                    append(AppLog.dumpSystemLogcat())
                }
                hevLog()?.let { (name, content) ->
                    appendLine()
                    appendLine("=== $name ===")
                    append(content.takeLast(MAX_ONSCREEN_CHARS))
                }
            }.takeLast(MAX_ONSCREEN_CHARS)

            withContext(Dispatchers.Main) {
                logsText.text = text
            }
        }
    }

    private fun hevLog(): Pair<String, String>? = try {
        val file = java.io.File(cacheDir, "hev.log")
        if (file.exists() && file.length() > 0) {
            val text = file.readText()
            // cap to the storage budget even while the tunnel is still
            // running and appending (the file itself is trimmed on stop)
            val capped = if (text.length > AppLog.LOG_FILE_MAX_BYTES) {
                text.substring(text.length - AppLog.LOG_FILE_MAX_BYTES)
                    .substringAfter('\n', text)
            } else {
                text
            }
            "hev-socks5-tunnel (${file.length()} bytes)" to capped
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Writes the full log (in-app buffer + logcat of this process) so the
     * file can be shared as-is.
     */
    private fun saveToFile(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // The file always contains the FULL logs (screen shows a
                // trimmed copy), so nothing is lost when sharing it
                val content = buildString {
                    appendLine(AppLog.dump())
                    appendLine()
                    appendLine("=== logcat (this process) ===")
                    append(AppLog.dumpSystemLogcat())
                    hevLog()?.let { (name, content) ->
                        appendLine()
                        appendLine("=== $name ===")
                        append(content)
                    }
                }

                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Cannot open $uri")

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LogsActivity, R.string.logs_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@LogsActivity,
                        getString(R.string.logs_save_error, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        private const val MAX_ONSCREEN_CHARS = 120_000
    }
}
