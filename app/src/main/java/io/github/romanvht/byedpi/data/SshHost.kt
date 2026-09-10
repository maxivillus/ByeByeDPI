package io.github.romanvht.byedpi.data

data class SshHost(
    var id: String = "",
    var name: String = "",
    var hostname: String = "",
    var port: Int = 22,
    var username: String = "",
    var authType: AuthType = AuthType.PASSWORD,
    var password: String? = null,
    var keyPath: String? = null,
    var keyPassword: String? = null,
    var pubkeyId: String? = null,
    var keepAliveSeconds: Int = 30,
    var knownHostKey: HostKey? = null,
) {
    enum class AuthType { PASSWORD, KEY, ANY }

    data class HostKey(
        var algorithm: String? = null,
        var fingerprint: String? = null,
    )
}
