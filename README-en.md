<div align="center">
  <p>
    <img src="https://github.com/romanvht/ByeDPIAndroid/raw/master/.github/images/app.svg" alt="Логотип ByeDPI" width="200" />
  </p>
  <h1>ByeByeDPI Android</h1>
  <p>
    <a href="README.md">Русский</a> |
    English |
    <a href="README-tr.md">Türkçe</a>
  </p>
  <p>
    <a href="https://github.com/maxivillus/ByeByeDPI/actions/workflows/build.yml"><img src="https://github.com/maxivillus/ByeByeDPI/actions/workflows/build.yml/badge.svg" alt="Build Status" /></a>
    <a href="https://github.com/maxivillus/ByeByeDPI/releases"><img src="https://img.shields.io/github/downloads/maxivillus/ByeByeDPI/total" alt="Downloads" /></a>
    <a href="https://github.com/maxivillus/ByeByeDPI/blob/main/LICENSE"><img src="https://img.shields.io/github/license/maxivillus/ByeByeDPI" alt="License" /></a>
  </p>
</div>

> [!IMPORTANT]
> This is a **fork** of [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) that adds an
> **SSH tunnel** (connect to your own server and route app traffic through it) and **SSH key
> management**. Everything else — ByeDPI itself, VPN/Proxy modes, the strategy tester — is
> unchanged. Report base-app issues upstream; report SSH-related issues in this repository.

An Android application that runs ByeDPI locally and redirects all traffic through it.

For stable operation, you may need to adjust the settings. More information about the available options can be found in the [ByeDPI documentation](https://github.com/hufrea/byedpi/blob/main/README.md).

This application is not a VPN. It uses Android’s VPN mode to redirect traffic, but it does not send any data to a remote server. It does not encrypt your traffic or hide your IP address.

> With the **SSH tunnel** enabled this changes: traffic is sent to the server you configure,
> so your public IP becomes the server's address and the server's owner can see unencrypted
> host names (SNI). Without the SSH tunnel the app behaves exactly as described above.

The application has only one official website:
https://byebyedpi.xyz

---

### Features

* Automatically starts the service when the device boots
* Saves command-line parameter lists
* Improved compatibility with Android TV/BOX devices
* Per-app split tunneling
* Settings import and export
* SSH tunnel through your own server (host list, password or key authentication)

### SSH tunnel

Settings → “SSH tunnel” → “SSH hosts”: add a server, select it and enable “Use SSH tunnel”.

App traffic goes through your SSH server, while the SSH connection itself is established
through ByeDPI (desync is applied to the SSH stream):

```
apps → VPN (tun2socks) → SSH forwarder → ByeDPI (desync) → SSH server → internet
```

* Authentication with a password or a private key (OpenSSH, Ed25519 included)
* Key management similar to ConnectBot: generate RSA/ECDSA/Ed25519/DSA, import
  (file or clipboard), password re-encryption, OpenSSH/PEM export, public key for `authorized_keys`
* The server host key is remembered on first connect (TOFU), its fingerprint is shown in the host editor
* “App logs” screen (Settings → SSH tunnel) for tunnel diagnostics with file export
* DNS works through mapdns (over TCP); UDP and QUIC do not pass through SSH — apps fall back to TCP
* Keeping SSH on port 443 is recommended: the connection looks less like SSH to DPI
* For desync to apply inside the tunnel, remove the protocol filter in the desync settings
  (the default `-Kt,h` covers TLS/HTTP only, while inside the tunnel the traffic looks like SSH)

### Usage

* Enable the corresponding option in the settings to use automatic startup.
* It is recommended to connect to the VPN once in order to accept the permission request.
* After that, the application will automatically start the service when the device boots, depending on the selected mode: VPN or Proxy.
* Comprehensive community guide: [ByeByeDPI-Manual](https://byebyedpi.xyz)

### Installing prebuilt APKs

Download built APKs from [Releases](../../releases) or from the
[Actions](../../actions/workflows/build.yml) tab (latest build artifact).

* `*-debug.apk` — **installable as is** (signed with the debug key), the easiest option.
* `*-release.apk` — available only when signing secrets are configured (see below); smaller
  and faster, use these for regular use.

> The app is not published on Google Play or IzzyOnDroid — only builds from this repository.

### Building

Requirements: **JDK 17**, Android SDK (platform 36, build-tools 36.0.0),
**NDK 27.0.12077973** and **CMake 3.22.1** (versions are pinned in the project).

1. Clone the repository with its submodules:
```bash
git clone --recurse-submodules https://github.com/maxivillus/ByeByeDPI.git
cd ByeByeDPI
```
2. **On Windows only** — fix the submodule symlinks once (see the explanation below):
```bash
bash scripts/fix_windows_symlinks.sh
```
3. Build the APKs:
```bash
./gradlew assembleDebug      # debug build, signed with the debug key
./gradlew assembleRelease    # release build (unsigned without signing secrets)
```
4. The APKs appear in `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`.

The `local.properties` file with the SDK path (`sdk.dir=...`) is created by Android Studio
or can be set manually.

#### Why the Windows helper script is needed

In the `hev-socks5-tunnel`, `hev-task-system` and `yaml` repositories some headers are
symlinks. Git on Windows (`core.symlinks=false`, the default without Developer Mode)
checks them out as text files containing a path. The compiler then sees empty headers and
the build fails with `unknown type name 'HevObjectAtomic'`.
`scripts/fix_windows_symlinks.sh` replaces those stubs with the real file contents.
On Linux and macOS the script does nothing — symlinks are proper there.

#### Signed releases (optional)

To have CI produce signed release APKs, add four secrets in
**Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `BYEDPI_KEYSTORE_BASE64` | your keystore in base64: `base64 -w0 release.keystore` |
| `BYEDPI_KEYSTORE_PASSWORD` | keystore password |
| `BYEDPI_KEY_ALIAS` | key alias |
| `BYEDPI_KEY_PASSWORD` | key password |

Without these secrets the build still succeeds, but release APKs stay unsigned
(they cannot be installed) and debug APKs are shipped as artifacts instead.

### Continuous integration (GitHub Actions)

`.github/workflows/build.yml` builds APKs on every push to `main`/`master`, on pull
requests and on demand (**Run workflow**). Artifacts (`byebyedpi-apk-<sha>`, kept for
30 days) are available on the **Actions** tab.

Pushing a tag like `v1.7.9` creates a GitHub Release with APKs: signed when signing
secrets are configured, otherwise with debug builds.

### Signing Certificate Hash

SHA-256 (debug key, builds from this repository):
`77:45:10:75:AC:EA:40:64:06:47:5D:74:D4:59:88:3A:49:A6:40:51:FA:F3:2E:42:F7:18:F3:F9:77:7A:8D:FB`

> The hash belongs to the original upstream signature. If you build a release with your
> own key, the signature will differ.

### Dependencies

* [ByeDPI](https://github.com/hufrea/byedpi)
* [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
* [sshlib](https://github.com/connectbot/sshlib) — the ConnectBot SSH engine (for the SSH tunnel).
  **Vendored as source** (`app/src/sshlib-java`, Apache-2.0) instead of a Maven dependency, so
  that channel-open failures the stock library swallows silently can be logged.

### Acknowledgements

* [hufrea](https://github.com/hufrea) — for [ByeDPI](https://github.com/hufrea/byedpi)
* [dovecoteescapee](https://github.com/dovecoteescapee) — for the original implementation of [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid)
