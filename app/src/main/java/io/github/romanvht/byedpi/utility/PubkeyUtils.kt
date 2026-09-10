package io.github.romanvht.byedpi.utility

import android.util.Log
import com.trilead.ssh2.crypto.Base64
import com.trilead.ssh2.crypto.OpenSSHKeyEncoder
import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.PEMEncoder
import com.trilead.ssh2.crypto.PublicKeyUtils
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey
import com.trilead.ssh2.crypto.keys.Ed25519Provider
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey
import io.github.romanvht.byedpi.data.Pubkey
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.DSAPublicKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays

/*
 * Key handling ported from ConnectBot (Apache License 2.0, Copyright Kenny Root):
 * storage format, PEM/PKCS#8 parsing and OpenSSH export rely on the same
 * sshlib (Trilead fork) API that ConnectBot uses.
 */
object PubkeyUtils {
    private const val TAG = "PubkeyUtils"

    init {
        Ed25519Provider.insertIfNeeded()
    }

    const val PKCS8_START: String = "-----BEGIN PRIVATE KEY-----"
    const val PKCS8_END: String = "-----END PRIVATE KEY-----"

    const val MAX_IMPORT_SIZE = 32 * 1024

    // Size in bytes of salt to use.
    private const val SALT_SIZE = 8

    // Number of iterations for password hashing. PKCS#5 recommends 1000
    private const val ITERATIONS = 1000

    private val ECDSA_SIZES = intArrayOf(256, 384, 521)

    enum class SshKeyType(
        val storedName: String,
        val keyFactoryAlgorithm: String?,
        val minBits: Int,
        val maxBits: Int,
        val defaultBits: Int,
        val fixedBits: Boolean,
    ) {
        RSA("RSA", "RSA", 1024, 16384, 2048, false),
        DSA("DSA", "DSA", 1024, 1024, 1024, true),
        EC("EC", "EC", 256, 521, 256, false),
        ED25519("Ed25519", null, 255, 255, 255, true),
        IMPORTED("IMPORTED", null, 0, 0, 0, true);

        companion object {
            fun fromStoredType(value: String?): SshKeyType? =
                when (value?.lowercase()) {
                    "rsa", "ssh-rsa", "rsa-sha2-256", "rsa-sha2-512" -> RSA
                    "dsa", "ssh-dss" -> DSA
                    "ec", "ecdsa" -> EC
                    "ed25519", "eddsa", "ssh-ed25519" -> ED25519
                    "imported" -> IMPORTED
                    else -> if (value?.startsWith("ecdsa-sha2-") == true) EC else null
                }

            fun fromJavaAlgorithm(value: String): SshKeyType? =
                when (value.lowercase()) {
                    "rsa" -> RSA
                    "dsa" -> DSA
                    "ec", "ecdsa" -> EC
                    "ed25519", "eddsa" -> ED25519
                    else -> null
                }
        }
    }

    class BadPasswordException : Exception()

    class KeyParseException(message: String?) : Exception(message)

    fun formatKey(key: Key): String {
        val algo = key.algorithm
        val fmt = key.format
        val encoded = key.encoded
        return "Key[algorithm=" + algo + ", format=" + fmt +
            ", bytes=" + encoded.size + "]"
    }

    @Throws(Exception::class)
    private fun encrypt(cleartext: ByteArray?, secret: String): ByteArray {
        val salt = ByteArray(SALT_SIZE)

        val ciphertext = Encryptor.encrypt(salt, ITERATIONS, secret, cleartext)
            ?: throw IllegalArgumentException("Encryption failed: ciphertext is null")

        val complete = ByteArray(salt.size + ciphertext.size)

        System.arraycopy(salt, 0, complete, 0, salt.size)
        System.arraycopy(ciphertext, 0, complete, salt.size, ciphertext.size)

        Arrays.fill(salt, 0x00.toByte())
        Arrays.fill(ciphertext, 0x00.toByte())

        return complete
    }

    @Throws(Exception::class)
    private fun decrypt(saltAndCiphertext: ByteArray, secret: String): ByteArray? {
        val salt = ByteArray(SALT_SIZE)
        val ciphertext = ByteArray(saltAndCiphertext.size - salt.size)

        System.arraycopy(saltAndCiphertext, 0, salt, 0, salt.size)
        System.arraycopy(saltAndCiphertext, salt.size, ciphertext, 0, ciphertext.size)

        return Encryptor.decrypt(salt, ITERATIONS, secret, ciphertext)
    }

    @Throws(Exception::class)
    fun getEncodedPrivate(pk: PrivateKey, secret: String?): ByteArray {
        val encoded = pk.encoded
        if (secret == null || secret.isEmpty()) {
            return encoded
        }
        return encrypt(pk.encoded, secret)
    }

    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    fun decodePrivate(encoded: ByteArray?, keyType: String?): PrivateKey? {
        val privateKeyBytes = encoded ?: throw InvalidKeySpecException("Missing private key data")
        val sshKeyType = SshKeyType.fromStoredType(keyType)
            ?: throw NoSuchAlgorithmException("Unsupported key type: $keyType")
        if (sshKeyType == SshKeyType.ED25519) {
            return Ed25519PrivateKey(PKCS8EncodedKeySpec(privateKeyBytes))
        }

        val algorithm = sshKeyType.keyFactoryAlgorithm
            ?: throw NoSuchAlgorithmException("Unsupported key type: $keyType")
        val privKeySpec = PKCS8EncodedKeySpec(privateKeyBytes)
        val kf = KeyFactory.getInstance(algorithm)
        return kf.generatePrivate(privKeySpec)
    }

    @Throws(Exception::class)
    fun decodePrivate(encoded: ByteArray, keyType: String?, secret: String?): PrivateKey? =
        if (secret != null && secret.isNotEmpty()) {
            decodePrivate(decrypt(encoded, secret), keyType)
        } else {
            decodePrivate(encoded, keyType)
        }

    @Throws(NoSuchAlgorithmException::class, InvalidKeySpecException::class)
    fun decodePublic(encoded: ByteArray?, keyType: String?): PublicKey {
        val publicKeyBytes = encoded ?: throw InvalidKeySpecException("Missing public key data")
        val sshKeyType = SshKeyType.fromStoredType(keyType)
            ?: throw NoSuchAlgorithmException("Unsupported key type: $keyType")
        if (sshKeyType == SshKeyType.ED25519) {
            return Ed25519PublicKey(X509EncodedKeySpec(publicKeyBytes))
        }

        val algorithm = sshKeyType.keyFactoryAlgorithm
            ?: throw NoSuchAlgorithmException("Unsupported key type: $keyType")
        val pubKeySpec = X509EncodedKeySpec(publicKeyBytes)
        val kf = KeyFactory.getInstance(algorithm)
        return kf.generatePublic(pubKeySpec)
    }

    /**
     * Build a usable KeyPair from a stored [Pubkey].
     * For imported keys [password] is the passphrase of the original PEM.
     */
    @Throws(BadPasswordException::class)
    fun convertToKeyPair(pubkey: Pubkey, password: String?): KeyPair? {
        return if (SshKeyType.IMPORTED.storedName == pubkey.type) {
            // load specific key using pem format
            try {
                PEMDecoder.decode(
                    String(pubkey.privateKey!!, charset("UTF-8")).toCharArray(),
                    password,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Cannot decode imported key", e)
                throw BadPasswordException()
            }
        } else {
            // load using internal generated format
            try {
                val privKey = decodePrivate(pubkey.privateKey!!, pubkey.type, password)
                val pubKey = decodePublic(pubkey.publicKey, pubkey.type)

                KeyPair(pubKey, privKey)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot decode pubkey from storage", e)
                throw BadPasswordException()
            }
        }
    }

    fun generateKeyPair(keyType: SshKeyType, bits: Int, entropy: ByteArray): KeyPair {
        val random = SecureRandom()
        // Work around JVM bug (as in ConnectBot)
        random.nextInt()
        random.setSeed(entropy)

        val keyPairGen = KeyPairGenerator.getInstance(keyType.storedName)
        keyPairGen.initialize(normalizeBits(keyType, bits), random)
        return keyPairGen.generateKeyPair()
    }

    fun normalizeBits(keyType: SshKeyType, bits: Int): Int =
        when (keyType) {
            SshKeyType.EC -> ECDSA_SIZES.minByOrNull { kotlin.math.abs(it - bits) } ?: ECDSA_SIZES[0]
            SshKeyType.ED25519, SshKeyType.DSA -> keyType.defaultBits
            else -> bits.coerceIn(keyType.minBits, keyType.maxBits).let { it - (it % 8) }
        }

    fun keyBits(pubkey: Pubkey): Int? = try {
        when (SshKeyType.fromStoredType(pubkey.type)) {
            SshKeyType.ED25519 -> 256
            SshKeyType.RSA -> (decodePublic(pubkey.publicKey, pubkey.type) as RSAPublicKey)
                .modulus.bitLength()
            SshKeyType.DSA -> (decodePublic(pubkey.publicKey, pubkey.type) as DSAPublicKey)
                .params.p.bitLength()
            SshKeyType.EC -> (decodePublic(pubkey.publicKey, pubkey.type) as ECPublicKey)
                .params.curve.field.getFieldSize()
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * OpenSSH-style SHA256 fingerprint of the public key wire blob.
     */
    fun fingerprint(pubkey: Pubkey): String? = try {
        if (SshKeyType.IMPORTED.storedName == pubkey.type) {
            val pair = PEMDecoder.decode(String(pubkey.privateKey!!, charset("UTF-8")).toCharArray(), pubkey.keyPassword)
            authorizedKeysFormat(pair.public, pubkey.nickname)?.let { fingerprintOfWire(it) }
        } else {
            val pk = decodePublic(pubkey.publicKey, pubkey.type)
            authorizedKeysFormat(pk, pubkey.nickname)?.let { fingerprintOfWire(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun fingerprintOfWire(authorizedLine: String): String? = try {
        val blob = authorizedLine.trim().split(Regex("\\s+")).getOrNull(1)
            ?: return null
        val decoded = Base64.decode(blob.toCharArray())
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(decoded)
        "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest)
    } catch (e: Exception) {
        null
    }

    fun authorizedKeysFormat(pk: PublicKey, comment: String): String? = try {
        PublicKeyUtils.toAuthorizedKeysFormat(pk, comment)
    } catch (e: Exception) {
        null
    }

    /**
     * OpenSSH-style SHA256 fingerprint of a KeyPair's public part, matching
     * `ssh-keygen -lf` output for comparison against authorized_keys.
     */
    fun fingerprintOfKeyPair(pair: KeyPair): String? = try {
        authorizedKeysFormat(pair.public, "fingerprint")?.let { fingerprintOfWire(it) }
    } catch (e: Exception) {
        null
    }

    fun exportPublicKeyAuthorized(pubkey: Pubkey): String? {
        if (SshKeyType.IMPORTED.storedName == pubkey.type) {
            throw KeyParseException("Cannot export public key from imported key")
        }
        val pk = decodePublic(pubkey.publicKey, pubkey.type)
        return authorizedKeysFormat(pk, pubkey.nickname)
    }

    fun exportPrivateOpenSSH(pubkey: Pubkey, password: String?, passphrase: String?): String? {
        if (SshKeyType.IMPORTED.storedName == pubkey.type) {
            return String(pubkey.privateKey ?: ByteArray(0), charset("UTF-8"))
        }
        val pk = decodePrivate(pubkey.privateKey!!, pubkey.type, password)
            ?: throw KeyParseException("Failed to decode private key")
        val pub = decodePublic(pubkey.publicKey, pubkey.type)
        return OpenSSHKeyEncoder.exportOpenSSH(pk, pub, pubkey.nickname, passphrase)
    }

    fun exportPrivatePem(pubkey: Pubkey, password: String?): String? {
        if (SshKeyType.IMPORTED.storedName == pubkey.type) {
            return String(pubkey.privateKey ?: ByteArray(0), charset("UTF-8"))
        }
        val pk = decodePrivate(pubkey.privateKey!!, pubkey.type, password)
            ?: throw KeyParseException("Failed to decode private key")
        return PEMEncoder.encodePrivateKey(pk, null)
    }

    /**
     * Re-encrypt a generated key, mirroring ConnectBot's editor: an encrypted
     * key requires the old password, empty [newPassword] removes encryption.
     */
    @Throws(Exception::class)
    fun changePassword(pubkey: Pubkey, oldPassword: String?, newPassword: String?) {
        val wasEncrypted = pubkey.encrypted
        val privateKeyBytes = pubkey.privateKey
            ?: throw KeyParseException("No private key data")
        val pk = decodePrivate(privateKeyBytes, pubkey.type, if (wasEncrypted) oldPassword else null)
            ?: throw BadPasswordException()

        pubkey.privateKey = getEncodedPrivate(pk, newPassword)
        pubkey.encrypted = !newPassword.isNullOrEmpty()
        pubkey.keyPassword = newPassword?.takeIf { it.isNotEmpty() }
    }

    sealed class ImportResult {
        class Success(val pubkey: Pubkey) : ImportResult()
        class NeedsPassword(val keyData: ByteArray, val nickname: String) : ImportResult()
        object Failed : ImportResult()
    }

    /**
     * Parse a key file / text in OpenSSH or PEM (including PKCS#8) format.
     */
    fun parseKeyBytes(keyData: ByteArray, nickname: String): ImportResult {
        val keyString = String(keyData)

        // Try PEMDecoder first (handles OpenSSH and traditional PEM formats)
        try {
            val struct = PEMDecoder.parsePEM(keyString.toCharArray())
            val encrypted = PEMDecoder.isPEMEncrypted(struct)

            return if (!encrypted) {
                val kp = PEMDecoder.decode(struct, null)
                ImportResult.Success(makeImported(keyData, nickname, null))
            } else {
                ImportResult.NeedsPassword(keyData, nickname)
            }
        } catch (e: Exception) {
            Log.d(TAG, "PEMDecoder failed, trying PKCS#8", e)
        }

        // Fallback: unencrypted PKCS#8 (-----BEGIN PRIVATE KEY-----)
        try {
            val decoded = readPkcs8Body(keyString)
            if (decoded != null) {
                OpenSSHKeyEncoder.recoverKeyPair(decoded)
                return ImportResult.Success(makeImported(keyData, nickname, null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PKCS#8 key", e)
        }

        return ImportResult.Failed
    }

    /**
     * Decrypt a passphrase-protected imported key with ConnectBot's import
     * options: either keep the original text (with the passphrase stored for
     * unattended unlock) or convert into internal encrypted storage.
     */
    fun decryptAndImport(
        keyData: ByteArray,
        nickname: String,
        password: String,
        encrypt: Boolean,
        encryptPassword: String?,
    ): Pubkey? = try {
        val keyString = String(keyData)
        val kp = PEMDecoder.decode(keyString.toCharArray(), password)

        if (encrypt && !encryptPassword.isNullOrEmpty()) {
            val algorithm = SshKeyType.fromJavaAlgorithm(kp.private.algorithm)?.storedName
                ?: kp.private.algorithm
            Pubkey(
                id = SshHostUtils.newId(),
                nickname = nickname,
                type = algorithm,
                privateKey = getEncodedPrivate(kp.private, encryptPassword),
                publicKey = kp.public.encoded,
                encrypted = true,
                keyPassword = encryptPassword,
                createdDate = System.currentTimeMillis(),
            )
        } else {
            makeImported(keyData, nickname, password)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decrypt imported key", e)
        null
    }

    /**
     * Imported keys keep their original PEM/OpenSSH text so they can be
     * exported as-is; [passphrase] is stored for unattended unlock.
     */
    private fun makeImported(keyData: ByteArray, nickname: String, passphrase: String?): Pubkey =
        Pubkey(
            id = SshHostUtils.newId(),
            nickname = nickname,
            type = SshKeyType.IMPORTED.storedName,
            privateKey = keyData,
            publicKey = ByteArray(0),
            encrypted = false,
            keyPassword = passphrase,
            createdDate = System.currentTimeMillis(),
        )

    private fun readPkcs8Body(keyString: String): ByteArray? {
        val reader = BufferedReader(InputStreamReader(ByteArrayInputStream(keyString.toByteArray())))
        val keyBytes = ByteArrayOutputStream()
        var line: String?
        var inKey = false

        while (reader.readLine().also { line = it } != null) {
            when {
                line == PKCS8_START -> inKey = true
                line == PKCS8_END -> break
                inKey -> keyBytes.write(line!!.toByteArray(Charsets.US_ASCII))
            }
        }

        return if (keyBytes.size() > 0) {
            Base64.decode(keyBytes.toString().toCharArray())
        } else {
            null
        }
    }
}
