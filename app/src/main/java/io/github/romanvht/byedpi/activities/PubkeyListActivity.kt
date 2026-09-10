package io.github.romanvht.byedpi.activities

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.adapters.PubkeyAdapter
import io.github.romanvht.byedpi.core.PubkeyVault
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.utility.ClipboardUtils
import io.github.romanvht.byedpi.utility.PubkeyStorage
import io.github.romanvht.byedpi.utility.PubkeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class PubkeyListActivity : BaseActivity() {

    private enum class ExportKind { PUBLIC, OPENSSH, PEM, OPENSSH_ENCRYPTED }

    private lateinit var adapter: PubkeyAdapter
    private lateinit var emptyView: TextView

    private var pendingExport: Pair<ExportKind, Pubkey>? = null
    private var pendingExportPassphrase: String? = null
    private var pendingImportData: ByteArray? = null
    private var pendingImportNickname: String? = null
    private var pendingImportPassword: String? = null

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    private val createDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val (kind, key) = pendingExport ?: return@registerForActivityResult
        val passphrase = pendingExportPassphrase
        pendingExport = null
        pendingExportPassphrase = null
        if (uri != null) exportToUri(kind, key, uri, passphrase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pubkey_list)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        emptyView = findViewById(R.id.pubkeys_empty)

        adapter = PubkeyAdapter(
            context = this,
            onToggle = { key -> toggleLoaded(key) },
            onEdit = { key ->
                startActivity(
                    android.content.Intent(this, PubkeyEditorActivity::class.java)
                        .putExtra(PubkeyEditorActivity.EXTRA_KEY_ID, key.id),
                )
            },
            onAction = { key, actionId -> handleAction(key, actionId) },
        )

        val recyclerView = findViewById<RecyclerView>(R.id.pubkeys_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.pubkey_add).setOnClickListener {
            startActivity(android.content.Intent(this, PubkeyGenerateActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_pubkey_list, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_import_file -> {
            pickKeyFile.launch(arrayOf("*/*"))
            true
        }
        R.id.action_import_clipboard -> {
            ClipboardUtils.paste(this)?.let { text ->
                startImport(PubkeyUtils.parseKeyBytes(text.toByteArray(Charsets.UTF_8), importedNickname()))
            } ?: showToast(R.string.pubkey_error_clipboard_empty)
            true
        }
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val keys = PubkeyStorage.getKeys(this)
        adapter.update(keys)
        emptyView.visibility = if (keys.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Loads/unloads a key in memory, like ConnectBot's key list toggle.
     * A stored password unlocks silently; without one the user is asked.
     */
    private fun toggleLoaded(key: Pubkey) {
        if (PubkeyVault.isLoaded(key.id)) {
            PubkeyVault.unload(key.id)
            refresh()
            Toast.makeText(this, R.string.pubkey_locked, Toast.LENGTH_SHORT).show()
            return
        }

        if (key.encrypted && key.keyPassword == null) {
            val input = EditText(this).apply {
                setSingleLine()
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.pubkey_unlock)
                .setMessage(getString(R.string.pubkey_unlock_message, key.nickname))
                .setView(input)
                .setPositiveButton(R.string.pubkey_unlock) { _, _ ->
                    loadKey(key, input.text.toString())
                }
                .setNegativeButton(R.string.ssh_cancel, null)
                .show()
        } else {
            loadKey(key, null)
        }
    }

    private fun loadKey(key: Pubkey, password: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                PubkeyVault.load(key, password)
                withContext(Dispatchers.Main) {
                    refresh()
                    Toast.makeText(this@PubkeyListActivity, R.string.pubkey_unlocked, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PubkeyListActivity, R.string.pubkey_error_load, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleAction(key: Pubkey, actionId: Int) = when (actionId) {
        R.id.action_edit -> {
            startActivity(
                android.content.Intent(this, PubkeyEditorActivity::class.java)
                    .putExtra(PubkeyEditorActivity.EXTRA_KEY_ID, key.id),
            )
        }
        R.id.action_copy_public -> copyToClipboard(key) { PubkeyUtils.exportPublicKeyAuthorized(it) }
        R.id.action_copy_openssh -> copyToClipboard(key) { PubkeyUtils.exportPrivateOpenSSH(it, it.keyPassword, null) }
        R.id.action_copy_pem -> copyToClipboard(key) { PubkeyUtils.exportPrivatePem(it, it.keyPassword) }
        R.id.action_copy_encrypted -> askPassphrase(key) { k, passphrase ->
            copyToClipboard(k) { PubkeyUtils.exportPrivateOpenSSH(k, k.keyPassword, passphrase) }
        }
        R.id.action_export_public -> startFileExport(ExportKind.PUBLIC, key)
        R.id.action_export_openssh -> startFileExport(ExportKind.OPENSSH, key)
        R.id.action_export_pem -> startFileExport(ExportKind.PEM, key)
        R.id.action_export_encrypted -> askPassphrase(key) { k, passphrase ->
            startFileExportWithPassphrase(ExportKind.OPENSSH_ENCRYPTED, k, passphrase)
        }
        R.id.action_delete -> confirmDelete(key)
        else -> {}
    }

    private fun copyToClipboard(key: Pubkey, producer: (Pubkey) -> String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = producer(key)
                withContext(Dispatchers.Main) {
                    if (text != null) {
                        ClipboardUtils.copy(this@PubkeyListActivity, text, "SSH key")
                    } else {
                        showToast(R.string.pubkey_error_export)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(R.string.pubkey_error_export)
                }
            }
        }
    }

    private fun startFileExport(kind: ExportKind, key: Pubkey) {
        pendingExport = kind to key
        createDocument.launch(suggestedFilename(kind, key))
    }

    private fun startFileExportWithPassphrase(kind: ExportKind, key: Pubkey, passphrase: String) {
        pendingExport = kind to key
        pendingExportPassphrase = passphrase
        createDocument.launch(suggestedFilename(kind, key))
    }

    private fun askPassphrase(key: Pubkey, onOk: (Pubkey, String) -> Unit) {
        val input = EditText(this).apply {
            setSingleLine()
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = getString(R.string.pubkey_export_passphrase_hint)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_export_passphrase_title)
            .setMessage(R.string.pubkey_export_passphrase_message)
            .setView(input)
            .setPositiveButton(R.string.pubkey_ok) { _, _ ->
                onOk(key, input.text.toString())
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    private fun suggestedFilename(kind: ExportKind, key: Pubkey): String {
        val name = key.nickname.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return when (kind) {
            ExportKind.PUBLIC -> "$name.pub"
            ExportKind.PEM -> "$name.pem"
            ExportKind.OPENSSH -> name
            ExportKind.OPENSSH_ENCRYPTED -> "$name-encrypted"
        }
    }

    private fun exportToUri(kind: ExportKind, key: Pubkey, uri: Uri, passphrase: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = when (kind) {
                    ExportKind.PUBLIC -> PubkeyUtils.exportPublicKeyAuthorized(key)
                    ExportKind.OPENSSH -> PubkeyUtils.exportPrivateOpenSSH(key, key.keyPassword, null)
                    ExportKind.PEM -> PubkeyUtils.exportPrivatePem(key, key.keyPassword)
                    ExportKind.OPENSSH_ENCRYPTED ->
                        PubkeyUtils.exportPrivateOpenSSH(key, key.keyPassword, passphrase)
                } ?: throw IllegalStateException("Export failed")

                contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("Cannot open $uri")

                withContext(Dispatchers.Main) {
                    showToast(R.string.pubkey_exported)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.pubkey_error_export_with, e.message ?: ""))
                }
            }
        }
    }

    private fun confirmDelete(key: Pubkey) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_delete_title)
            .setMessage(getString(R.string.pubkey_delete_confirm, key.nickname))
            .setPositiveButton(R.string.ssh_delete) { _, _ ->
                PubkeyStorage.deleteKey(this, key.id)
                refresh()
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    // ---------- Import ----------

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val keyData = readKeyFromUri(uri)
                val nickname = getFilenameFromUri(uri) ?: importedNickname()
                withContext(Dispatchers.Main) {
                    startImport(PubkeyUtils.parseKeyBytes(keyData, nickname))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast(R.string.pubkey_error_parse)
                }
            }
        }
    }

    private fun startImport(result: PubkeyUtils.ImportResult) {
        when (result) {
            is PubkeyUtils.ImportResult.Success -> confirmNickname(result.pubkey)
            is PubkeyUtils.ImportResult.NeedsPassword -> askImportPassword(result)
            is PubkeyUtils.ImportResult.Failed -> showToast(R.string.pubkey_error_parse)
        }
    }

    private fun askImportPassword(pending: PubkeyUtils.ImportResult.NeedsPassword) {
        val input = EditText(this).apply {
            setSingleLine()
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_import_password_title)
            .setMessage(getString(R.string.pubkey_import_password_message, pending.nickname))
            .setView(input)
            .setPositiveButton(R.string.pubkey_ok) { _, _ ->
                pendingImportData = pending.keyData
                pendingImportNickname = pending.nickname
                pendingImportPassword = input.text.toString()
                showImportOptionsDialog(pending.nickname, input.text.toString())
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    /**
     * Mirrors ConnectBot's encrypted import dialog: rename the key and choose
     * whether to re-encrypt into internal storage or keep the original text
     * (reusing the decryption passphrase for unattended unlock).
     */
    private fun showImportOptionsDialog(initialNickname: String, decryptPassword: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        val nameInput = EditText(this).apply {
            setText(initialNickname)
            setSingleLine()
            hint = getString(R.string.pubkey_import_nickname_hint)
        }
        val encryptSwitch = CheckBox(this).apply {
            text = getString(R.string.pubkey_import_encrypt_key)
        }
        val reuseSwitch = CheckBox(this).apply {
            text = getString(R.string.pubkey_import_reuse_password)
            isChecked = true
        }
        val encryptInput = EditText(this).apply {
            setSingleLine()
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            hint = getString(R.string.pubkey_password_optional)
            visibility = View.GONE
        }

        encryptSwitch.setOnCheckedChangeListener { _, checked ->
            encryptInput.visibility = if (checked) View.VISIBLE else View.GONE
            reuseSwitch.visibility = if (checked) View.VISIBLE else View.GONE
        }

        container.addView(nameInput)
        container.addView(encryptSwitch)
        container.addView(reuseSwitch)
        container.addView(encryptInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_import_encrypted_title)
            .setView(container)
            .setPositiveButton(R.string.pubkey_ok) { _, _ ->
                val nickname = nameInput.text.toString().trim().ifEmpty { initialNickname }
                val encrypt = encryptSwitch.isChecked
                val encryptPassword = when {
                    !encrypt -> null
                    reuseSwitch.isChecked -> pendingImportPassword
                    else -> encryptInput.text.toString().takeIf { it.isNotEmpty() }
                        ?: pendingImportPassword
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val keyData = pendingImportData
                    val password = pendingImportPassword
                    val imported = if (keyData != null && password != null) {
                        PubkeyUtils.decryptAndImport(keyData, nickname, password, encrypt, encryptPassword)
                    } else {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (imported != null) {
                            saveImported(imported)
                        } else {
                            showToast(R.string.pubkey_error_bad_password)
                        }
                    }
                }
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    private fun confirmNickname(pubkey: Pubkey) {
        val input = EditText(this).apply {
            setText(pubkey.nickname)
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_import_title)
            .setMessage(R.string.pubkey_import_nickname_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                pubkey.nickname = input.text.toString().trim().ifEmpty { pubkey.nickname }
                saveImported(pubkey)
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    private fun saveImported(pubkey: Pubkey) {
        if (PubkeyStorage.findByNickname(this, pubkey.nickname) != null) {
            pubkey.nickname = "${pubkey.nickname} (${System.currentTimeMillis() % 10000})"
        }
        PubkeyStorage.upsertKey(this, pubkey)
        pendingImportData = null
        pendingImportNickname = null
        pendingImportPassword = null
        refresh()
    }

    private fun readKeyFromUri(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var read: Int
            var total = 0

            while (stream.read(buffer).also { read = it } != -1) {
                total += read
                if (total > PubkeyUtils.MAX_IMPORT_SIZE) {
                    throw IllegalStateException("File too large")
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } ?: throw IllegalStateException("Cannot open $uri")
    }

    private fun getFilenameFromUri(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)?.substringBeforeLast('.')
                }
            }
        }
        return null
    }

    private fun importedNickname(): String = getString(R.string.pubkey_imported_default_name)

    private fun showToast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
    }

    private fun showToast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
