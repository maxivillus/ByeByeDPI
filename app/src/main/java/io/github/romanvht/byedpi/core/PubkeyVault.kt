package io.github.romanvht.byedpi.core

import io.github.romanvht.byedpi.utility.AppLog
import io.github.romanvht.byedpi.data.Pubkey
import io.github.romanvht.byedpi.utility.PubkeyUtils
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store of unlocked ("loaded") keys, mirroring ConnectBot's
 * loaded-keys concept. Loaded keys are used by the SSH tunnel for hosts
 * with "any stored key" authentication. The set is not persisted: keys
 * flagged as startup are re-loaded automatically when the tunnel starts.
 */
object PubkeyVault {
    private const val TAG = "PubkeyVault"

    private val loaded = ConcurrentHashMap<String, KeyPair>()

    fun isLoaded(id: String): Boolean = loaded.containsKey(id)

    fun loadedIds(): Set<String> = loaded.keys.toSet()

    fun getLoaded(id: String): KeyPair? = loaded[id]

    /**
     * Unlock a key and keep it in memory. Uses the stored key password
     * unless an explicit one is provided (interactive unlock).
     */
    @Throws(Exception::class)
    fun load(key: Pubkey, password: String? = null): KeyPair {
        val pair = if (password != null) {
            PubkeyUtils.convertToKeyPair(key, password)
        } else {
            PubkeyUtils.convertToKeyPair(key, key.keyPassword)
        } ?: throw PubkeyUtils.BadPasswordException()

        loaded[key.id] = pair
        AppLog.i(TAG, "Key '${key.nickname}' (${key.type}) loaded into memory")
        return pair
    }

    fun loadOrGet(key: Pubkey): KeyPair? = loaded.getOrPut(key.id) {
        try {
            load(key)
        } catch (e: Exception) {
            AppLog.w(TAG, "Key '${key.nickname}' load failed: ${e.message}")
            null
        }
    }

    fun unload(id: String) {
        val pair = loaded.remove(id)
        if (pair != null) {
            AppLog.i(TAG, "Key unloaded from memory (id=$id)")
        }
    }

    fun loadedKeyPairs(): List<KeyPair> = loaded.values.toList()
}
