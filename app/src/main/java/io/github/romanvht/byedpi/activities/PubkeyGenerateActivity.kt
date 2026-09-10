package io.github.romanvht.byedpi.activities

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.utility.PubkeyStorage
import io.github.romanvht.byedpi.utility.PubkeyUtils
import io.github.romanvht.byedpi.view.EntropyView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PubkeyGenerateActivity : BaseActivity() {

    private lateinit var nicknameView: EditText
    private lateinit var typeView: Spinner
    private lateinit var bitsView: EditText
    private lateinit var bitsContainer: LinearLayout
    private lateinit var passwordView: EditText
    private lateinit var password2View: EditText
    private lateinit var startupSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var confirmationSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var entropyView: EntropyView
    private lateinit var entropyProgress: TextView
    private lateinit var generateButton: Button

    private var generating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pubkey_generate)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        nicknameView = findViewById(R.id.pubkey_nickname)
        typeView = findViewById(R.id.pubkey_keytype)
        bitsView = findViewById(R.id.pubkey_bits)
        bitsContainer = findViewById(R.id.pubkey_bits_container)
        passwordView = findViewById(R.id.pubkey_password)
        password2View = findViewById(R.id.pubkey_password2)
        startupSwitch = findViewById(R.id.pubkey_generate_startup)
        confirmationSwitch = findViewById(R.id.pubkey_generate_confirmation)
        entropyView = findViewById(R.id.pubkey_entropy)
        entropyProgress = findViewById(R.id.pubkey_entropy_progress)
        generateButton = findViewById(R.id.pubkey_generate)

        val types = listOf(
            PubkeyUtils.SshKeyType.RSA,
            PubkeyUtils.SshKeyType.EC,
            PubkeyUtils.SshKeyType.ED25519,
            PubkeyUtils.SshKeyType.DSA,
        )

        typeView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            types.map { typeLabel(it) },
        )
        typeView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onTypeChanged(types[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        entropyView.progressListener = { collected, required ->
            entropyProgress.text = getString(R.string.pubkey_entropy_progress, collected, required)
        }
        entropyView.progressListener?.invoke(0, EntropyView.REQUIRED_BYTES)

        generateButton.setOnClickListener { generate() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun typeLabel(type: PubkeyUtils.SshKeyType): String = when (type) {
        PubkeyUtils.SshKeyType.RSA -> "RSA"
        PubkeyUtils.SshKeyType.EC -> "ECDSA"
        PubkeyUtils.SshKeyType.ED25519 -> "Ed25519"
        PubkeyUtils.SshKeyType.DSA -> "DSA"
        PubkeyUtils.SshKeyType.IMPORTED -> "IMPORTED"
    }

    private fun onTypeChanged(type: PubkeyUtils.SshKeyType) {
        bitsContainer.visibility = if (type.fixedBits) View.GONE else View.VISIBLE
        bitsView.setText(type.defaultBits.toString())
    }

    private fun generate() {
        if (generating) return

        val nickname = nicknameView.text.toString().trim()
        if (nickname.isEmpty()) {
            Toast.makeText(this, R.string.pubkey_error_nickname, Toast.LENGTH_LONG).show()
            return
        }
        if (PubkeyStorage.findByNickname(this, nickname) != null) {
            Toast.makeText(this, R.string.pubkey_error_nickname_exists, Toast.LENGTH_LONG).show()
            return
        }

        val password = passwordView.text.toString()
        if (password != password2View.text.toString()) {
            Toast.makeText(this, R.string.pubkey_error_password_mismatch, Toast.LENGTH_LONG).show()
            return
        }

        if (!entropyView.isFull) {
            Toast.makeText(this, R.string.pubkey_error_entropy, Toast.LENGTH_LONG).show()
            return
        }

        val type = selectedType()
        val bits = bitsView.text.toString().toIntOrNull() ?: type.defaultBits

        generating = true
        generateButton.isEnabled = false

        val entropy = entropyView.takeEntropy()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val keyPair = PubkeyUtils.generateKeyPair(type, bits, entropy)
                val key = Pubkey(
                    id = PubkeyStorage.newId(),
                    nickname = nickname,
                    type = type.storedName,
                    privateKey = PubkeyUtils.getEncodedPrivate(keyPair.private, password.ifEmpty { null }),
                    publicKey = keyPair.public.encoded,
                    encrypted = password.isNotEmpty(),
                    keyPassword = password.ifEmpty { null },
                    startup = startupSwitch.isChecked,
                    confirmation = confirmationSwitch.isChecked,
                    createdDate = System.currentTimeMillis(),
                )
                PubkeyStorage.upsertKey(this@PubkeyGenerateActivity, key)

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PubkeyGenerateActivity,
                        R.string.pubkey_generated,
                        Toast.LENGTH_SHORT,
                    ).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@PubkeyGenerateActivity,
                        getString(R.string.pubkey_error_generate, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                    generating = false
                    generateButton.isEnabled = true
                }
            }
        }
    }

    private fun selectedType(): PubkeyUtils.SshKeyType = when (typeView.selectedItemPosition) {
        0 -> PubkeyUtils.SshKeyType.RSA
        1 -> PubkeyUtils.SshKeyType.EC
        2 -> PubkeyUtils.SshKeyType.ED25519
        else -> PubkeyUtils.SshKeyType.DSA
    }
}
