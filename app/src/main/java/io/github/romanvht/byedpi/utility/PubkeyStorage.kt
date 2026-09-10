package io.github.romanvht.byedpi.utility

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.data.Pubkey
import java.util.UUID

object PubkeyStorage {
    private const val KEYS_KEY = "pubkeys_json"

    private val gson = Gson()
    private val keysType = object : TypeToken<MutableList<Pubkey>>() {}.type

    fun getKeys(context: Context): MutableList<Pubkey> {
        val json = context.getPreferences().getString(KEYS_KEY, null) ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<Pubkey>>(json, keysType) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveKeys(context: Context, keys: List<Pubkey>) {
        context.getPreferences().edit(commit = true) {
            putString(KEYS_KEY, gson.toJson(keys))
        }
    }

    fun findKey(context: Context, id: String?): Pubkey? {
        if (id == null) return null
        return getKeys(context).find { it.id == id }
    }

    fun findByNickname(context: Context, nickname: String): Pubkey? =
        getKeys(context).find { it.nickname.equals(nickname, ignoreCase = true) }

    fun upsertKey(context: Context, key: Pubkey) {
        val keys = getKeys(context)
        val index = keys.indexOfFirst { it.id == key.id }
        if (index >= 0) keys[index] = key else keys.add(key)
        saveKeys(context, keys)
    }

    /**
     * Delete a key and detach it from any SSH hosts referencing it.
     */
    fun deleteKey(context: Context, id: String) {
        val keys = getKeys(context)
        keys.removeAll { it.id == id }
        saveKeys(context, keys)

        val hosts = SshHostUtils.getHosts(context)
        var changed = false
        hosts.forEach { host ->
            if (host.pubkeyId == id) {
                host.pubkeyId = null
                changed = true
            }
        }
        if (changed) SshHostUtils.saveHosts(context, hosts)
    }

    fun setDefault(context: Context, id: String) {
        val keys = getKeys(context)
        keys.forEach { it.isDefault = it.id == id }
        saveKeys(context, keys)
    }

    fun getDefault(context: Context): Pubkey? =
        getKeys(context).find { it.isDefault }

    fun incrementTimesUsed(context: Context, id: String) {
        val key = findKey(context, id) ?: return
        key.timesUsed += 1
        upsertKey(context, key)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
