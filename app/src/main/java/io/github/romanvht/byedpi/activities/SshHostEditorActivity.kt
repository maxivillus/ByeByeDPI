package io.github.romanvht.byedpi.activities

import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.data.SshHost
import io.github.romanvht.byedpi.utility.PubkeyStorage
import io.github.romanvht.byedpi.utility.SshHostUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SshHostEditorActivity : BaseActivity() {

    private var host: SshHost? = null
    private var selectedKeyPath: String? = null
    private var selectedPubkeyId: String? = null
    private var managedKeys: List<Pubkey> = emptyList()
    private var forgetKey = false

    private lateinit var nameView: EditText
    private lateinit var hostnameView: EditText
    private lateinit var portView: EditText
    private lateinit var usernameView: EditText
    private lateinit var authTypeView: Spinner
    private lateinit var passwordContainer: LinearLayout
    private lateinit var passwordView: EditText
    private lateinit var keyContainer: LinearLayout
    private lateinit var pubkeySelectView: Spinner
    private lateinit var keySelectButton: Button
    private lateinit var keyFileNameView: TextView
    private lateinit var keyPasswordView: EditText
    private lateinit var keepAliveView: EditText
    private lateinit var knownKeyContainer: LinearLayout
    private lateinit var knownKeyFpView: TextView

    private val pickKey = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importKey(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ssh_host_editor)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        host = SshHostUtils.findHost(this, intent.getStringExtra(EXTRA_HOST_ID))

        nameView = findViewById(R.id.ssh_name)
        hostnameView = findViewById(R.id.ssh_hostname)
        portView = findViewById(R.id.ssh_port)
        usernameView = findViewById(R.id.ssh_username)
        authTypeView = findViewById(R.id.ssh_auth_type)
        passwordContainer = findViewById(R.id.ssh_password_container)
        passwordView = findViewById(R.id.ssh_password)
        keyContainer = findViewById(R.id.ssh_key_container)
        pubkeySelectView = findViewById(R.id.ssh_pubkey_select)
        keySelectButton = findViewById(R.id.ssh_key_select)
        keyFileNameView = findViewById(R.id.ssh_key_file_name)
        keyPasswordView = findViewById(R.id.ssh_key_password)
        keepAliveView = findViewById(R.id.ssh_keepalive)
        knownKeyContainer = findViewById(R.id.ssh_known_key_container)
        knownKeyFpView = findViewById(R.id.ssh_known_key_fp)

        managedKeys = PubkeyStorage.getKeys(this)
        selectedPubkeyId = host?.pubkeyId

        val keyOptions = mutableListOf(getString(R.string.ssh_key_source_file))
        keyOptions.addAll(managedKeys.map { it.nickname })
        pubkeySelectView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            keyOptions,
        )
        pubkeySelectView.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedPubkeyId = managedKeys.getOrNull(position - 1)?.id
                updateKeyFileUi()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        authTypeView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.ssh_auth_password),
                getString(R.string.ssh_auth_any),
                getString(R.string.ssh_auth_key),
            ),
        )
        authTypeView.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateAuthPanels(position)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.ssh_key_select).setOnClickListener {
            pickKey.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.ssh_forget_key).setOnClickListener {
            forgetKey = true
            knownKeyContainer.visibility = View.GONE
        }
        findViewById<Button>(R.id.ssh_save).setOnClickListener { save() }

        fillFields()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun fillFields() {
        val h = host ?: run {
            portView.setText(DEFAULT_PORT.toString())
            keepAliveView.setText(DEFAULT_KEEPALIVE.toString())
            return
        }

        nameView.setText(h.name)
        hostnameView.setText(h.hostname)
        portView.setText(h.port.toString())
        usernameView.setText(h.username)
        authTypeView.setSelection(
            when (h.authType) {
                SshHost.AuthType.KEY -> 2
                SshHost.AuthType.ANY -> 1
                else -> 0
            }
        )
        passwordView.setText(h.password ?: "")
        selectedKeyPath = h.keyPath
        h.keyPath?.let { keyFileNameView.text = File(it).name }
        keyPasswordView.setText(h.keyPassword ?: "")
        keepAliveView.setText(h.keepAliveSeconds.toString())

        val managedIndex = managedKeys.indexOfFirst { it.id == selectedPubkeyId }
        pubkeySelectView.setSelection(if (managedIndex >= 0) managedIndex + 1 else 0)
        updateKeyFileUi()

        val knownKey = h.knownHostKey
        if (knownKey?.fingerprint != null && !forgetKey) {
            knownKeyFpView.text = "${knownKey.algorithm ?: "?"}\n${knownKey.fingerprint}"
            knownKeyContainer.visibility = View.VISIBLE
        }
    }

    private fun updateAuthPanels(position: Int) {
        passwordContainer.visibility = if (position == 0) View.VISIBLE else View.GONE
        keyContainer.visibility = if (position == 2) View.VISIBLE else View.GONE
    }

    private fun updateKeyFileUi() {
        val fileMode = selectedPubkeyId == null
        keySelectButton.visibility = if (fileMode) View.VISIBLE else View.GONE
        keyFileNameView.visibility = if (fileMode) View.VISIBLE else View.GONE
        keyPasswordView.visibility = if (fileMode) View.VISIBLE else View.GONE
    }

    private fun save() {
        val hostname = hostnameView.text.toString().trim()
        val username = usernameView.text.toString().trim()
        val port = portView.text.toString().toIntOrNull() ?: DEFAULT_PORT
        val keepAlive = keepAliveView.text.toString().toIntOrNull() ?: DEFAULT_KEEPALIVE

        if (hostname.isEmpty() || username.isEmpty()) {
            Toast.makeText(this, R.string.ssh_error_required, Toast.LENGTH_LONG).show()
            return
        }
        if (port !in 1..65535) {
            Toast.makeText(this, R.string.ssh_error_port, Toast.LENGTH_LONG).show()
            return
        }

        val keyAuth = authTypeView.selectedItemPosition == 2
        val useManagedKey = selectedPubkeyId != null
        if (keyAuth && !useManagedKey && selectedKeyPath == null) {
            Toast.makeText(this, R.string.ssh_error_key_missing, Toast.LENGTH_LONG).show()
            return
        }

        val authType = when (authTypeView.selectedItemPosition) {
            1 -> SshHost.AuthType.ANY
            2 -> SshHost.AuthType.KEY
            else -> SshHost.AuthType.PASSWORD
        }

        val saved = SshHost(
            id = host?.id ?: SshHostUtils.newId(),
            name = nameView.text.toString().trim(),
            hostname = hostname,
            port = port,
            username = username,
            authType = authType,
            password = passwordView.text.toString().takeIf { it.isNotEmpty() },
            keyPath = if (keyAuth && !useManagedKey) selectedKeyPath else null,
            keyPassword = if (keyAuth && !useManagedKey) keyPasswordView.text.toString().takeIf { it.isNotEmpty() } else null,
            pubkeyId = if (keyAuth) selectedPubkeyId else null,
            keepAliveSeconds = keepAlive,
            knownHostKey = if (forgetKey) null else host?.knownHostKey,
        )

        SshHostUtils.upsertHost(this, saved)
        if (SshHostUtils.getActiveHost(this) == null) {
            SshHostUtils.setActiveHostId(this, saved.id)
        }

        finish()
    }

    private fun importKey(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(filesDir, "ssh_keys").apply { mkdirs() }
                val dest = File(dir, "key_${System.currentTimeMillis()}.pem")

                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: throw IOException("Cannot open $uri")

                selectedKeyPath = dest.absolutePath

                withContext(Dispatchers.Main) {
                    keyFileNameView.text = dest.name
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SshHostEditorActivity,
                        R.string.ssh_error_key_import,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    companion object {
        const val EXTRA_HOST_ID = "host_id"
        private const val DEFAULT_PORT = 22
        private const val DEFAULT_KEEPALIVE = 30
    }
}
