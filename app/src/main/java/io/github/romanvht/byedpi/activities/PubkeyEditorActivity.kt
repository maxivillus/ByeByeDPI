package io.github.romanvht.byedpi.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.utility.PubkeyStorage
import io.github.romanvht.byedpi.utility.PubkeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Mirrors ConnectBot's pubkey editor: rename, password change with old
 * password verification, unlock-at-startup and confirm-use flags.
 */
class PubkeyEditorActivity : BaseActivity() {

    private var key: Pubkey? = null
    private var wasEncrypted = false
    private var wrongPassword = false

    private lateinit var nicknameView: EditText
    private lateinit var detailsView: TextView
    private lateinit var defaultSwitch: SwitchMaterial
    private lateinit var startupSwitch: SwitchMaterial
    private lateinit var confirmationSwitch: SwitchMaterial
    private lateinit var passwordSection: LinearLayout
    private lateinit var oldPasswordView: EditText
    private lateinit var newPasswordView: EditText
    private lateinit var newPassword2View: EditText
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pubkey_editor)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        key = PubkeyStorage.findKey(this, intent.getStringExtra(EXTRA_KEY_ID))

        nicknameView = findViewById(R.id.pubkey_edit_nickname)
        detailsView = findViewById(R.id.pubkey_edit_details)
        defaultSwitch = findViewById(R.id.pubkey_edit_default)
        startupSwitch = findViewById(R.id.pubkey_edit_startup)
        confirmationSwitch = findViewById(R.id.pubkey_edit_confirmation)
        passwordSection = findViewById(R.id.pubkey_edit_password_section)
        oldPasswordView = findViewById(R.id.pubkey_edit_old_password)
        newPasswordView = findViewById(R.id.pubkey_edit_new_password)
        newPassword2View = findViewById(R.id.pubkey_edit_new_password2)
        saveButton = findViewById(R.id.pubkey_edit_save)
        val deleteButton = findViewById<Button>(R.id.pubkey_edit_delete)

        val k = key ?: run {
            finish()
            return
        }

        wasEncrypted = k.encrypted

        nicknameView.setText(k.nickname)
        detailsView.text = buildDetails(k)

        defaultSwitch.isChecked = k.isDefault
        startupSwitch.isChecked = k.startup
        confirmationSwitch.isChecked = k.confirmation

        // Imported keys keep their original passphrase, ConnectBot's editor
        // only re-encrypts internally stored keys
        val imported = PubkeyUtils.SshKeyType.fromStoredType(k.type) == PubkeyUtils.SshKeyType.IMPORTED
        passwordSection.visibility = if (imported) View.GONE else View.VISIBLE
        oldPasswordView.visibility = if (wasEncrypted) View.VISIBLE else View.GONE

        saveButton.setOnClickListener { save() }
        deleteButton.setOnClickListener { confirmDelete() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun buildDetails(k: Pubkey): String = buildString {
        append(k.type)
        PubkeyUtils.keyBits(k)?.let { append(" · ").append(it).append(" bit") }
        PubkeyUtils.fingerprint(k)?.let { append("\n").append(it) }
        append("\n")
        append(getString(R.string.pubkey_created, DateFormat.getDateInstance().format(Date(k.createdDate))))
        append("\n")
        append(getString(R.string.pubkey_times_used, k.timesUsed))
    }

    private fun save() {
        val k = key ?: return
        val nickname = nicknameView.text.toString().trim()
        if (nickname.isEmpty()) {
            Toast.makeText(this, R.string.pubkey_error_nickname, Toast.LENGTH_LONG).show()
            return
        }
        if (!nickname.equals(k.nickname, ignoreCase = true) && PubkeyStorage.findByNickname(this, nickname) != null) {
            Toast.makeText(this, R.string.pubkey_error_nickname_exists, Toast.LENGTH_LONG).show()
            return
        }

        val newPassword = newPasswordView.text.toString()
        val newPassword2 = newPassword2View.text.toString()
        if (newPassword != newPassword2) {
            Toast.makeText(this, R.string.pubkey_error_password_mismatch, Toast.LENGTH_LONG).show()
            return
        }

        val oldPassword = oldPasswordView.text.toString()
        val imported = PubkeyUtils.SshKeyType.fromStoredType(k.type) == PubkeyUtils.SshKeyType.IMPORTED

        lifecycleScope.launch(Dispatchers.IO) {
            var failureRes: Int? = null
            var failureMsg: String? = null
            try {
                // Same predicate as ConnectBot's editor: an encrypted key only
                // re-encrypts when the old password is provided
                val needsPasswordChange =
                    (wasEncrypted && oldPassword.isNotEmpty()) ||
                        (!wasEncrypted && newPassword.isNotEmpty())

                if (!imported && needsPasswordChange) {
                    PubkeyUtils.changePassword(
                        k,
                        if (wasEncrypted) oldPassword else null,
                        newPassword.ifEmpty { null },
                    )
                }

                k.nickname = nickname
                k.startup = startupSwitch.isChecked
                k.confirmation = confirmationSwitch.isChecked
                PubkeyStorage.upsertKey(this@PubkeyEditorActivity, k)
            } catch (_: PubkeyUtils.BadPasswordException) {
                wrongPassword = true
                failureRes = R.string.pubkey_error_bad_password
            } catch (e: Exception) {
                failureMsg = e.message ?: ""
            }

            withContext(Dispatchers.Main) {
                when {
                    failureRes != null -> Toast.makeText(this@PubkeyEditorActivity, failureRes, Toast.LENGTH_LONG).show()
                    failureMsg != null -> Toast.makeText(
                        this@PubkeyEditorActivity,
                        getString(R.string.pubkey_error_save, failureMsg ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    else -> finish()
                }
            }
        }
    }

    private fun confirmDelete() {
        val k = key ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.pubkey_delete_title)
            .setMessage(getString(R.string.pubkey_delete_confirm, k.nickname))
            .setPositiveButton(R.string.ssh_delete) { _, _ ->
                PubkeyStorage.deleteKey(this, k.id)
                finish()
            }
            .setNegativeButton(R.string.ssh_cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_KEY_ID = "key_id"
    }
}
