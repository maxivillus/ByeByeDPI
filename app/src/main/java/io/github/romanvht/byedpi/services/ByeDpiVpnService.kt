package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.PubkeyVault
import io.github.romanvht.byedpi.core.SshTunnelManager
import io.github.romanvht.byedpi.core.TProxyService
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
import java.io.File
import java.io.IOException
import java.security.KeyPair

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var sshLocalPort: Int? = null
    private var sshStateJob: Job? = null

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"
        private const val SSH_CONNECT_TIMEOUT_MS: Long = 30_000
        private const val SSH_MAX_ATTEMPTS: Int = 10

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        tunFd?.close()
        SshTunnelManager.stop()
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
                    if (prepare(this@ByeDpiVpnService) == null) {
                        start()
                    }
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

            SERVICE_INTERFACE -> {
                Log.i(TAG, "Started by Android")

                if (getPreferences().mode() != Mode.VPN) {
                    Log.w(TAG, "Always-On disabled in proxy mode")
                    stopSelf()
                    return START_NOT_STICKY
                }

                lifecycleScope.launch {
                    start()
                }

                START_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            updateStatus(ServiceStatus.Connected)
            return
        }

        try {
            mutex.withLock {
                startProxy()
                startSshTunnelIfNeeded()
                startTun2Socks()
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: CancellationException) {
            // Service is being torn down; the real cause (if any) was
            // already recorded by whoever triggered the cancellation
            Log.w(TAG, "VPN start cancelled", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            AppLog.e(TAG, "Failed to start VPN: ${e.message}")
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
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
            try {
                withContext(Dispatchers.IO) {
                    stopProxy()
                    stopSshTunnel()
                    stopTun2Socks()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
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

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = byeDpiProxy.startProxy(preferences)

            delay(500)

            if (code != 0) {
                Log.e(TAG, "Proxy stopped with code $code")
                AppLog.w(TAG, "ciadpi exited with code $code")
                // Record the exit code: it is the most common hidden cause
                // of "VPN start failed"
                getPreferences().edit()
                    .putString("ssh_last_error", "ciadpi exited with code $code")
                    .commit()
                updateStatus(ServiceStatus.Failed)
                stopTun2Socks()
                stopSelf()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        try {
            byeDpiProxy.stopProxy()
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                byeDpiProxy.jniForceClose()
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

        sshLocalPort = SshTunnelManager.awaitConnected(SSH_CONNECT_TIMEOUT_MS)
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
        sshLocalPort = null
        if (SshTunnelManager.isRunning) {
            SshTunnelManager.stop()
        }
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            Log.w(TAG, "VPN field not null")
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val useSsh = sshLocalPort != null

        val (ip, port) = if (useSsh) {
            Pair("127.0.0.1", sshLocalPort!!.toString())
        } else {
            sharedPreferences.getProxyIpAndPort()
        }

        // mapdns intercepts DNS locally and answers with fake IPs from the
        // mapped network; later connections are sent as domain names over
        // SOCKS5, because the SSH forwarder has no UDP support
        val dns = if (useSsh) {
            SshHostUtils.MAPDNS_ADDRESS
        } else {
            sharedPreferences.getStringNotNull("dns_ip", "1.1.1.1")
        }
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")

            if (useSsh) {
                appendLine("mapdns:")
                appendLine("  address: ${SshHostUtils.MAPDNS_ADDRESS}")
                appendLine("  port: 53")
                appendLine("  network: ${SshHostUtils.MAPDNS_FAKE_NETWORK}")
                appendLine("  netmask: ${SshHostUtils.MAPDNS_FAKE_NETMASK}")
                appendLine("  cache-size: 10000")
            }

            appendLine("misc:")
            appendLine("  task-stack-size: 81920")

            if (useSsh) {
                // capture hev's own diagnostics into a file readable
                // from the logs screen
                runCatching { File(cacheDir, "hev.log").delete() }
                appendLine("  log-file: ${File(cacheDir, "hev.log").absolutePath}")
                appendLine("  log-level: debug")
            }

            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started. ip: $ip port: $port")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        if (tunFd == null) {
            Log.w(TAG, "VPN field is null, skipping")
            return
        }

        try {
            TProxyService.TProxyStopService()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop TProxyService", e)
        }

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        // The tunnel is down, so nothing appends to the log anymore:
        // cap it to the configured storage budget
        runCatching {
            AppLog.trimFileTail(File(cacheDir, "hev.log"))
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

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
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            QuickTileService.updateTile()
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            ByeDpiVpnService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("ByeDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = getPreferences()
        val listType = preferences.getStringNotNull("applist_type", "disable")
        val listedApps = preferences.getSelectedApps()

        when (listType) {
            "blacklist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в черный список", e)
                    }
                }

                builder.addDisallowedApplication(applicationContext.packageName)
            }

            "whitelist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в белый список", e)
                    }
                }
            }

            "disable" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }

        return builder
    }
}
