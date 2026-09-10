package io.github.romanvht.byedpi.data

/**
 * SSH key pair stored inside the app.
 *
 * Generated keys keep the private part as PKCS#8 bytes, optionally encrypted
 * with [keyPassword] via AES (see PubkeyUtils). Imported keys keep the raw
 * PEM/OpenSSH text in [privateKey] so they can be exported as-is; for keys
 * protected by a passphrase [keyPassword] holds it for unattended unlock.
 */
class Pubkey(
    var id: String = "",
    var nickname: String = "",
    var type: String = "",
    var privateKey: ByteArray? = null,
    var publicKey: ByteArray = ByteArray(0),
    var encrypted: Boolean = false,
    var keyPassword: String? = null,
    var isDefault: Boolean = false,
    var startup: Boolean = false,
    var confirmation: Boolean = false,
    var timesUsed: Int = 0,
    var createdDate: Long = 0,
)
