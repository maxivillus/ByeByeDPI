package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.PubkeyVault
import io.github.romanvht.byedpi.core.SshTunnelManager
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.security.KeyPair

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private val mutex = Mutex()
    private var sshStateJob: Job? = null

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"
        private const val SSH_CONNECT_TIMEOUT_MS: Long = 30_000
        private const val SSH_MAX_ATTEMPTS: Int = 10

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        startForeground()

        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch {
                    start()
                }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch {
                    stop()
                }
                START_NOT_STICKY
            }

            RESUME_ACTION -> {
                lifecycleScope.launch {
                    start()
                }
                START_STICKY
            }

            PAUSE_ACTION -> {
                lifecycleScope.launch {
                    stop()
                    createNotificationPause()
                }
                START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "Proxy already connected")
            updateStatus(ServiceStatus.Connected)
            return
        }

        try {
            mutex.withLock {
                startProxy()
                startSshTunnelIfNeeded()
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "Proxy start cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            AppLog.e(TAG, "Failed to start proxy: ${e.message}")
            if (SshHostUtils.isSshEnabled(this)) {
                // Keep the ciadpi exit code record: it outranks SSH errors
                val prefs = getPreferences()
                val current = prefs.getString("ssh_last_error", null)
                if (current == null || !current.startsWith("ciadpi exited")) {
                    prefs.edit()
                        .putString("ssh_last_error", e.message ?: e.javaClass.simpleName)
                        .commit()
                }
            }
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        // Cleanup must run regardless of the reported status: a failed start
        // leaves ciadpi running, and skipping it makes the next start fail
        // with "proxy already running" (exit code -1)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                stopProxy()
                stopSshTunnel()
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        proxy = ByeDpiProxy()
        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = proxy.startProxy(preferences)

            delay(500)

            if (code != 0) {
                Log.e(TAG, "Proxy stopped with code $code")
                AppLog.w(TAG, "ciadpi exited with code $code")
                getPreferences().edit()
                    .putString("ssh_last_error", "ciadpi exited with code $code")
                    .commit()
                updateStatus(ServiceStatus.Failed)
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        try {
            proxy.stopProxy()
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                proxy.jniForceClose()
            }

            proxyJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
        }

        Log.i(TAG, "Proxy stopped")
    }

    private suspend fun startSshTunnelIfNeeded() {
        if (!SshHostUtils.isSshEnabled(this)) return
        if (getPreferences().getBoolean("is_test_running", false)) return

        val host = SshHostUtils.getActiveHost(this)
            ?: SshHostUtils.getHosts(this).firstOrNull()?.also {
                SshHostUtils.setActiveHostId(this, it.id)
            }
            ?: throw IOException("SSH tunnel enabled but no host selected")

        val (ip, port) = getPreferences().getProxyIpAndPort()
        val upstreamPort = port.toIntOrNull() ?: 1080

        // Do not race the ciadpi startup: wait until it listens
        SshTunnelManager.awaitUpstream(ip, upstreamPort)

        // Pre-load keys marked as startup, like ConnectBot does
        preloadStartupKeys()
        val keyPair = resolveHostKey(host)
        val extraKeys = PubkeyVault.loadedKeyPairs()

        SshTunnelManager.start(
            host = host,
            upstreamIp = ip,
            upstreamPort = upstreamPort,
            listenPort = SshHostUtils.getLocalSshPort(this),
            maxAttempts = if (SshHostUtils.isStopOnFail(this)) SSH_MAX_ATTEMPTS else 0,
            keyPair = keyPair,
            extraKeys = extraKeys,
            onHostKeyAccepted = { h, algo, fingerprint ->
                SshHostUtils.saveKnownKey(this, h.id, algo, fingerprint)
            },
        )

        SshTunnelManager.awaitConnected(SSH_CONNECT_TIMEOUT_MS)
        getPreferences().edit().remove("ssh_last_error").commit()
        keyPair?.let { host.pubkeyId?.let { id -> PubkeyStorage.incrementTimesUsed(this, id) } }
        watchSshState()
    }

    private fun preloadStartupKeys() {
        PubkeyStorage.getKeys(this)
            .filter { it.startup }
            .forEach { pubkey ->
                if (!PubkeyVault.isLoaded(pubkey.id)) {
                    try {
                        PubkeyVault.load(pubkey)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot pre-load key '${pubkey.nickname}': ${e.message}")
                    }
                }
            }
    }

    private fun resolveHostKey(host: SshHost): KeyPair? {
        val pubkeyId = when (host.authType) {
            SshHost.AuthType.KEY -> host.pubkeyId
            SshHost.AuthType.ANY -> host.pubkeyId
                ?: PubkeyStorage.getDefault(this)?.id
            else -> null
        }
        if (pubkeyId == null) return null
        PubkeyVault.getLoaded(pubkeyId)?.let { return it }

        val pubkey = PubkeyStorage.findKey(this, pubkeyId) ?: return null
        return try {
            PubkeyVault.load(pubkey)
        } catch (e: Exception) {
            if (host.authType == SshHost.AuthType.KEY) {
                throw IOException("Cannot unlock SSH key '${pubkey.nickname}': ${e.message}")
            }
            null
        }
    }

    private fun watchSshState() {
        if (sshStateJob?.isActive == true) return

        sshStateJob = lifecycleScope.launch {
            SshTunnelManager.state.collect { s ->
                if (s is SshTunnelManager.State.Failed) {
                    Log.e(TAG, "SSH tunnel failed: ${s.error}")
                    stop()
                }
            }
        }
    }

    private fun stopSshTunnel() {
        sshStateJob?.cancel()
        sshStateJob = null
        if (SshTunnelManager.isRunning) {
            SshTunnelManager.stop()
        }
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "Proxy status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.Proxy
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.Proxy.ordinal)
        sendBroadcast(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            QuickTileService.updateTile()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SshTunnelManager.stop()
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            ByeDpiProxyService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiProxyService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }
}
