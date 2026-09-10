package io.github.romanvht.byedpi.core

import io.github.romanvht.byedpi.utility.AppLog
import io.github.romanvht.byedpi.utility.PubkeyUtils
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.crypto.PEMDecoder
import io.github.romanvht.byedpi.data.SshHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64

/**
 * Maintains a single SSH connection with a local SOCKS5 dynamic forwarder.
 * The connection is established through the local ciadpi proxy, so ByeDPI
 * desync applies to the SSH stream. App traffic reaches the tunnel via
 * the forwarder's SOCKS5 port.
 */
object SshTunnelManager {
    private const val TAG = "SshTunnelManager"

    sealed class State {
        object Idle : State()
        data class Connecting(val hostname: String) : State()
        data class Connected(val localPort: Int) : State()
        data class Reconnecting(val attempt: Int, val error: String?) : State()
        data class Failed(val error: String?) : State()
    }

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val KEX_TIMEOUT_MS = 15_000
    private const val START_BACKOFF_MS = 3_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val FORWARD_PORT_ATTEMPTS = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Last failure reason, for diagnostics UI. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Waits until the local ciadpi proxy accepts connections. Non-fatal:
     * returns false when it is still not listening, and the SSH reconnect
     * loop will surface the precise SOCKS error instead.
     */
    suspend fun awaitUpstream(ip: String, port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // The probe MUST run off the main thread: a socket connect from
            // the UI dispatcher throws NetworkOnMainThreadException
            val listening = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(ip, port), 500)
                    }
                    true
                } catch (e: Exception) {
                    AppLog.w(
                        TAG,
                        "Upstream $ip:$port not ready yet: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                    false
                }
            }
            if (listening) {
                AppLog.i(TAG, "Upstream proxy $ip:$port is listening")
                return true
            }
            delay(250)
        }
        AppLog.e(
            TAG,
            "Upstream proxy $ip:$port did not start listening in ${timeoutMs}ms; " +
                "ciadpi may have exited (see 'ciadpi exited with code' record)",
        )
        return false
    }

    private var desired = false
    private var job: Job? = null
    private var connection: Connection? = null
    private var boundPort = 0

    val isRunning: Boolean get() = synchronized(this) { desired }

    /**
     * Port of the local SOCKS5 forwarder once connected, 0 otherwise.
     */
    val localPort: Int
        get() = if (_state.value is State.Connected) boundPort else 0

    fun start(
        host: SshHost,
        upstreamIp: String,
        upstreamPort: Int,
        listenPort: Int,
        maxAttempts: Int = 0,
        keyPair: KeyPair? = null,
        extraKeys: List<KeyPair> = emptyList(),
        onHostKeyAccepted: (SshHost, String, String) -> Unit = { _, _, _ -> },
    ) {
        synchronized(this) {
            if (desired) return
            desired = true
            job = scope.launch {
                connectLoop(host, upstreamIp, upstreamPort, listenPort, maxAttempts, keyPair, extraKeys, onHostKeyAccepted)
            }
        }
    }

    /**
     * Suspends until the forwarder is listening. Returns its port.
     * @throws IOException on failure or timeout.
     */
    suspend fun awaitConnected(timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val s = _state.value) {
                is State.Connected -> return s.localPort
                is State.Failed -> throw IOException(s.error ?: "SSH tunnel failed")
                else -> {}
            }
            delay(100)
        }
        throw IOException("SSH tunnel did not connect in ${timeoutMs / 1000} s")
    }

    fun stop() {
        synchronized(this) {
            if (!desired) return
            desired = false
            job?.cancel()
            job = null
            closeConnection()
            _state.value = State.Idle
        }
    }

    private suspend fun connectLoop(
        host: SshHost,
        upstreamIp: String,
        upstreamPort: Int,
        listenPort: Int,
        maxAttempts: Int,
        keyPair: KeyPair?,
        extraKeys: List<KeyPair>,
        onHostKeyAccepted: (SshHost, String, String) -> Unit,
    ) {
        var attempt = 0

        while (desired) {
            attempt += 1

            if (attempt == 1) {
                _state.value = State.Connecting(host.hostname)
            }

            try {
                val port = connectOnce(host, upstreamIp, upstreamPort, listenPort, keyPair, extraKeys, onHostKeyAccepted)
                AppLog.i(TAG, "SSH tunnel connected, forwarder on port $port")

                attempt = 0
                lastError = null
                _state.value = State.Connected(port)
                keepAliveLoop(host)

                if (!desired) return
                throw IOException("SSH connection closed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeConnection()
                lastError = e.message ?: e.javaClass.simpleName
                if (!desired) return

                if (maxAttempts in 1..attempt) {
                    AppLog.e(TAG, "SSH tunnel failed after $attempt attempts", e)
                    _state.value = State.Failed(e.message ?: "SSH tunnel failed")
                    return
                }

                AppLog.w(TAG, "SSH attempt $attempt failed: ${e.message}")
                _state.value = State.Reconnecting(attempt, e.message)
            }

            delay(minOf(MAX_BACKOFF_MS, START_BACKOFF_MS * attempt))
        }
    }

    private fun connectOnce(
        host: SshHost,
        upstreamIp: String,
        upstreamPort: Int,
        listenPort: Int,
        keyPair: KeyPair?,
        extraKeys: List<KeyPair>,
        onHostKeyAccepted: (SshHost, String, String) -> Unit,
    ): Int {
        val conn = Connection(host.hostname, host.port)
        synchronized(this) { connection = conn }

        AppLog.i(
            TAG,
            "SSH connect: ${host.username}@${host.hostname}:${host.port} " +
                "via SOCKS5 $upstreamIp:$upstreamPort, auth=${host.authType}",
        )

        try {
            conn.setProxyData(Socks5ProxyData(upstreamIp, upstreamPort))
            conn.connect(
                TofuHostKeyVerifier(host, onHostKeyAccepted),
                CONNECT_TIMEOUT_MS,
                KEX_TIMEOUT_MS,
            )
            AppLog.i(TAG, "TCP/KEX done, host key accepted")

            try {
                val methods = conn.getRemainingAuthMethods(host.username)
                AppLog.i(
                    TAG,
                    "Server allows auth methods: " +
                        (methods?.joinToString(", ") ?: "unknown"),
                )
            } catch (e: Exception) {
                AppLog.w(TAG, "Could not query auth methods: ${e.message}")
            }

            when (host.authType) {
                SshHost.AuthType.PASSWORD -> {
                    AppLog.i(TAG, "Authenticating with password")
                    if (!conn.authenticateWithPassword(host.username, host.password ?: "")) {
                        throw IOException("SSH authentication failed: password rejected by server")
                    }
                }

                SshHost.AuthType.KEY -> {
                    // keyPair comes from the app's pubkey storage; fall back
                    // to a raw key file selected in the host editor
                    val pair = keyPair ?: loadKeyPairFromFile(host)
                        ?: throw IOException("SSH key is not available")
                    if (!authenticateWithKeys(conn, host, listOf(pair))) {
                        throw IOException(
                            "SSH authentication failed: public key rejected by server " +
                                "(check authorized_keys and file permissions)",
                        )
                    }
                }

                SshHost.AuthType.ANY -> {
                    // "Use any available key": try the host key, then the
                    // default/startup keys, like ConnectBot's any-unlocked-key
                    val candidates = listOfNotNull(keyPair) + extraKeys
                    if (candidates.isEmpty()) throw IOException("No SSH keys available")
                    if (!authenticateWithKeys(conn, host, candidates)) {
                        throw IOException(
                            "SSH authentication failed: all ${candidates.size} public keys " +
                                "were rejected by server",
                        )
                    }
                }
            }
            AppLog.i(TAG, "Authenticated as ${host.username}")

            return bindForwarder(conn, listenPort)
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "SSH connect to ${host.hostname}:${host.port} failed: " +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            runCatching { conn.close() }
            synchronized(this) { connection = null }
            throw e
        }
    }

    private fun authenticateWithKeys(conn: Connection, host: SshHost, candidates: List<KeyPair>): Boolean {
        candidates.forEachIndexed { index, pair ->
            AppLog.i(
                TAG,
                "Trying pubkey auth, candidate ${index + 1}/${candidates.size} " +
                    "(${pair.public.algorithm}, fp=${PubkeyUtils.fingerprintOfKeyPair(pair)})",
            )
            if (conn.authenticateWithPublicKey(host.username, pair)) {
                AppLog.i(TAG, "Pubkey auth ok with candidate ${index + 1}")
                return true
            }
            AppLog.w(TAG, "Pubkey auth rejected for candidate ${index + 1}")
        }
        return false
    }

    private fun loadKeyPairFromFile(host: SshHost): KeyPair? {
        val path = host.keyPath ?: return null
        val pem = File(path).readText(Charsets.UTF_8)
        return PEMDecoder.decode(pem.toCharArray(), host.keyPassword)
            ?: throw IOException("Could not decode SSH key from $path")
    }

    private fun bindForwarder(conn: Connection, preferredPort: Int): Int {
        var port = preferredPort

        repeat(FORWARD_PORT_ATTEMPTS) {
            try {
                // Explicit IPv4 loopback: getLoopbackAddress() may return ::1
                // on Android, while tun2socks connects to 127.0.0.1
                conn.createDynamicPortForwarder(
                    InetSocketAddress(InetAddress.getByName("127.0.0.1"), port),
                )
                boundPort = port
                return port
            } catch (e: BindException) {
                port += 1
            }
        }

        throw IOException("No free port for SSH forwarder near $preferredPort")
    }

    private suspend fun keepAliveLoop(host: SshHost) {
        val intervalSec = host.keepAliveSeconds.takeIf { it > 0 } ?: 30

        while (desired) {
            delay(intervalSec * 1000L)
            if (!desired) return

            val conn = synchronized(this) { connection } ?: return
            try {
                withContext(Dispatchers.IO) {
                    conn.sendIgnorePacket()
                }
            } catch (e: Exception) {
                throw IOException("SSH keepalive failed: ${e.message}", e)
            }
        }
    }

    private fun closeConnection() {
        synchronized(this) {
            runCatching { connection?.close() }
            connection = null
            boundPort = 0
        }
    }
}

/**
 * Trust-on-first-use host key verifier: the first seen key is stored
 * with the host, later connections must present the same key.
 */
class TofuHostKeyVerifier(
    private val host: SshHost,
    private val onAccepted: (SshHost, String, String) -> Unit,
) : ServerHostKeyVerifier {

    override fun verifyServerHostKey(
        hostname: String?,
        port: Int,
        serverHostKeyAlgorithm: String?,
        serverHostKey: ByteArray?,
    ): Boolean {
        if (serverHostKeyAlgorithm == null || serverHostKey == null) return false

        val fingerprint = fingerprint(serverHostKey) ?: return false
        val stored = host.knownHostKey

        if (stored?.fingerprint != null) {
            return stored.algorithm == serverHostKeyAlgorithm && stored.fingerprint == fingerprint
        }

        onAccepted(host, serverHostKeyAlgorithm, fingerprint)
        return true
    }

    companion object {
        fun fingerprint(key: ByteArray): String? = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(key)
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        } catch (e: Exception) {
            null
        }
    }
}
