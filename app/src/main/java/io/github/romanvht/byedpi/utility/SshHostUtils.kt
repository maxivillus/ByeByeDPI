package io.github.romanvht.byedpi.utility

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.romanvht.byedpi.data.SshHost
import java.util.UUID

object SshHostUtils {
    private const val HOSTS_KEY = "ssh_hosts"
    private const val SSH_ENABLED_KEY = "ssh_enabled"
    private const val SSH_ACTIVE_HOST_KEY = "ssh_active_host_id"
    private const val SSH_LOCAL_PORT_KEY = "ssh_local_port"
    private const val SSH_FAIL_ACTION_KEY = "ssh_fail_action"

    // Fake DNS and IP pool for tun2socks mapdns: DNS works over TCP,
    // which is the only transport the SSH SOCKS forwarder supports
    const val MAPDNS_ADDRESS = "198.18.0.2"
    const val MAPDNS_FAKE_NETWORK = "100.64.0.0"
    const val MAPDNS_FAKE_NETMASK = "255.192.0.0"

    const val DEFAULT_SSH_LISTEN_PORT = 1081

    private val gson = Gson()
    private val hostsType = object : TypeToken<MutableList<SshHost>>() {}.type

    fun getHosts(context: Context): MutableList<SshHost> {
        val json = context.getPreferences().getString(HOSTS_KEY, null) ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<SshHost>>(json, hostsType) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveHosts(context: Context, hosts: List<SshHost>) {
        context.getPreferences().edit(commit = true) {
            putString(HOSTS_KEY, gson.toJson(hosts))
        }
    }

    fun findHost(context: Context, id: String?): SshHost? {
        if (id == null) return null
        return getHosts(context).find { it.id == id }
    }

    fun upsertHost(context: Context, host: SshHost) {
        val hosts = getHosts(context)
        val index = hosts.indexOfFirst { it.id == host.id }
        if (index >= 0) hosts[index] = host else hosts.add(host)
        saveHosts(context, hosts)
    }

    fun deleteHost(context: Context, id: String) {
        val hosts = getHosts(context)
        hosts.removeAll { it.id == id }
        saveHosts(context, hosts)
        if (context.getPreferences().getString(SSH_ACTIVE_HOST_KEY, null) == id) {
            setActiveHostId(context, null)
        }
    }

    fun saveKnownKey(context: Context, hostId: String, algorithm: String, fingerprint: String) {
        val host = findHost(context, hostId) ?: return
        host.knownHostKey = SshHost.HostKey(algorithm, fingerprint)
        upsertHost(context, host)
    }

    fun getActiveHost(context: Context): SshHost? =
        findHost(context, context.getPreferences().getString(SSH_ACTIVE_HOST_KEY, null))

    fun setActiveHostId(context: Context, id: String?) {
        context.getPreferences().edit(commit = true) {
            putString(SSH_ACTIVE_HOST_KEY, id)
        }
    }

    fun isSshEnabled(context: Context): Boolean =
        context.getPreferences().getBoolean(SSH_ENABLED_KEY, false)

    fun setSshEnabled(context: Context, enabled: Boolean) {
        context.getPreferences().edit(commit = true) {
            putBoolean(SSH_ENABLED_KEY, enabled)
        }
    }

    fun getLocalSshPort(context: Context): Int =
        context.getPreferences().getIntStringNotNull(SSH_LOCAL_PORT_KEY, DEFAULT_SSH_LISTEN_PORT)

    fun isStopOnFail(context: Context): Boolean =
        context.getPreferences().getStringNotNull(SSH_FAIL_ACTION_KEY, "hold") == "stop"

    fun newId(): String = UUID.randomUUID().toString()
}
