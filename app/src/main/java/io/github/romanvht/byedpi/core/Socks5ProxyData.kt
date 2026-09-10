package io.github.romanvht.byedpi.core

import io.github.romanvht.byedpi.utility.AppLog
import com.trilead.ssh2.ProxyData
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * sshlib proxy connector: routes the SSH connection through an external
 * SOCKS5 server (the local ciadpi proxy), so ciadpi desync applies
 * to the SSH stream itself.
 */
class Socks5ProxyData(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val proxyUser: String? = null,
    private val proxyPassword: String? = null,
) : ProxyData {

    override fun openConnection(hostname: String, port: Int, connectTimeout: Int): Socket {
        AppLog.i(TAG, "Connecting to $hostname:$port via SOCKS5 $proxyHost:$proxyPort")

        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(proxyHost, proxyPort), connectTimeout)
        try {
            socket.soTimeout = connectTimeout
            handshake(socket, hostname, port)
            socket.soTimeout = 0
        } catch (e: IOException) {
            runCatching { socket.close() }
            AppLog.e(TAG, "Handshake failed for $hostname:$port: ${e.message}")
            throw IOException("SOCKS5 proxy handshake failed: ${e.message}", e)
        }
        AppLog.i(TAG, "SOCKS5 tunnel to $hostname:$port established")
        return socket
    }

    private fun handshake(socket: Socket, hostname: String, port: Int) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = socket.getOutputStream()

        val useAuth = !proxyUser.isNullOrEmpty()

        // Greeting: offer no-auth, plus username/password when credentials are set
        output.write(
            if (useAuth) byteArrayOf(0x05, 0x02, 0x00, 0x02)
            else byteArrayOf(0x05, 0x01, 0x00)
        )
        output.flush()

        val version = input.readUnsignedByte()
        val method = input.readUnsignedByte()
        AppLog.i(TAG, "Greeting reply: version=$version method=0x%02X".format(method))
        if (version != 0x05) {
            throw IOException("SOCKS5 proxy replied with unsupported version $version")
        }

        when (method) {
            METHOD_NO_AUTH -> {}
            METHOD_USER_PASS -> {
                if (!useAuth) throw IOException("SOCKS5 proxy requires authentication")
                authenticate(input, output)
            }
            else -> throw IOException("SOCKS5 proxy has no acceptable auth method (0x%02X)".format(method))
        }

        sendConnect(output, hostname, port)
        readConnectReply(input)
    }

    private fun authenticate(input: DataInputStream, output: OutputStream) {
        val user = proxyUser!!.toByteArray(Charsets.UTF_8)
        val pass = (proxyPassword ?: "").toByteArray(Charsets.UTF_8)
        if (user.size > 255 || pass.size > 255) {
            throw IOException("SOCKS5 credentials too long")
        }

        output.write(
            byteArrayOf(0x01, user.size.toByte()) + user +
                    byteArrayOf(pass.size.toByte()) + pass
        )
        output.flush()

        if (input.readUnsignedByte() != 0x01) {
            throw IOException("Invalid SOCKS5 auth subnegotiation reply")
        }
        if (input.readUnsignedByte() != 0x00) {
            throw IOException("SOCKS5 authentication failed")
        }
        AppLog.i(TAG, "SOCKS5 username/password auth ok")
    }

    private fun sendConnect(output: OutputStream, hostname: String, port: Int) {
        // Send the domain name: ciadpi resolves it itself and keeps
        // the host list for its desync logic
        val host = hostname.toByteArray(Charsets.UTF_8)
        if (host.size > 255) throw IOException("Hostname too long for SOCKS5")

        val request = ByteArrayOutputStream().apply {
            write(0x05)
            write(CMD_CONNECT)
            write(0x00)
            write(ATYP_DOMAIN)
            write(host.size)
            write(host)
            write((port shr 8) and 0xFF)
            write(port and 0xFF)
        }
        request.writeTo(output)
        output.flush()
        AppLog.i(TAG, "CONNECT $hostname:$port sent (domain name)")
    }

    private fun readConnectReply(input: DataInputStream) {
        val version = input.readUnsignedByte()
        val reply = input.readUnsignedByte()
        input.readUnsignedByte() // reserved
        val atyp = input.readUnsignedByte()

        when (atyp) {
            ATYP_IPV4 -> input.readFully(ByteArray(4))
            ATYP_DOMAIN -> input.readFully(ByteArray(input.readUnsignedByte()))
            ATYP_IPV6 -> input.readFully(ByteArray(16))
            else -> throw IOException("Unknown SOCKS5 reply address type 0x%02X".format(atyp))
        }
        input.readFully(ByteArray(2)) // remote port

        AppLog.i(TAG, "CONNECT reply: version=$version reply=$reply")
        if (version != 0x05) throw IOException("Invalid SOCKS5 CONNECT reply version $version")
        if (reply != 0x00) {
            throw IOException("SOCKS5 CONNECT failed, reply code $reply")
        }
    }

    companion object {
        private const val TAG = "Socks5Proxy"

        private const val METHOD_NO_AUTH = 0x00
        private const val METHOD_USER_PASS = 0x02
        private const val CMD_CONNECT = 0x01
        private const val ATYP_IPV4 = 0x01
        private const val ATYP_DOMAIN = 0x03
        private const val ATYP_IPV6 = 0x04
    }
}
