# Application updates

Cove's in-process updater is available for Windows installer and portable
builds, and for the Android APK on phones, tablets, and televisions — one APK
carries the updater and both shells wire the update overlay. AUR installs remain
owned by `pacman`; standalone Flatpak bundles, tarballs, and macOS DMG
installations are replaced manually.

Automatic updates are enabled per device by default. Cove checks shortly after
launch and no more than once every 24 hours while the process remains open. It
downloads and verifies a newer stable release, but never interrupts playback:
the install/restart prompt appears after playback becomes idle. The Advanced
settings page provides the opt-out and a manual **Check now** action. Android
asks before downloading on a metered connection.

## Trust and staging

Each GitHub release contains `cove-update-manifest-v1.json` and its detached
Ed25519 signature. The manifest signs the release version, platform target,
exact asset name, byte count, and SHA-256 digest. Public verification keys are
embedded into the packaged application. The key id in downloaded JSON can only
select one of those embedded keys; it cannot introduce key material.

Manifest, signature, and payload responses are size-capped and streamed. Cove
rejects prereleases, duplicate or unsafe asset names, mismatched release tags,
unexpected GitHub asset URLs, overlong payloads, invalid signatures, and digest
mismatches. A staged payload is verified again after a process restart.

On Windows, the verified detached NSIS helper waits for Cove to exit, backs up
application-owned files, replaces them, and restarts Cove. A failed replacement
rolls back before restart. Installed and portable distributions use distinct
signed targets and marker files.

A pre-`1.0.0` Windows installation must be upgraded by hand, and the release
deliberately makes the old updater decline rather than try. Those builds shipped
their own Go updater, which looked for an asset named exactly
`cove-windows-amd64.zip` alongside a `.sha256` sibling and unpacked it over the
install directory. That archive now holds a different application entirely — a
Compose `Cove.exe` with `runtime/` and `app/`, rather than the Qt `cove_shell.exe`,
the Go `cove.exe`, and `web/` — and the old shell's restart handshake
(`cove.exe.new`, exit code 42) matches nothing in it, so applying it would leave
an install that no longer starts. Publishing the portable archive as
`cove-windows-amd64-portable.zip` means the old updater finds no matching asset
and fails closed, which is why that name must not be changed back. The current
updater is unaffected: it resolves the `windows-installer` and `windows-portable`
targets from the signed manifest and never reads the portable archive.

On Android, Cove additionally parses the APK before installation and requires a
newer version code, the same package id, and exactly the installed signing
certificate. Android's package installer retains its normal confirmation flow.
The `0.31.3` APK can upgrade directly when it was signed with the same production
keystore.

## Release key setup

Generate the offline Ed25519 key once and store only its base64 DER form in the
protected GitHub Actions secret `UPDATE_SIGNING_KEY_BASE64`:

```sh
openssl genpkey -algorithm ED25519 -out cove-update-private.pem
openssl pkey -in cove-update-private.pem -outform DER -out cove-update-private.der
base64 -w0 cove-update-private.der
```

Set the repository variable `UPDATE_SIGNING_KEY_ID` to a stable identifier such
as `cove-2026-1`. The workflow derives the public X.509 DER key, embeds it in
every package, signs the manifest only after all packages pass, and refuses to
replace an existing GitHub release. Keep the private PEM/DER backup outside the
repository and CI logs.

For key rotation, first publish a release signed by the old key with both old
and next public entries in `UPDATE_TRUSTED_PUBLIC_KEYS` (comma-separated
`key-id=base64-x509-der` values). After that bridge release is deployed, replace
the signing secret and `UPDATE_SIGNING_KEY_ID` with the next key while retaining
both public entries. Remove the old public key only in a later next-key-signed
release. A compromised key requires a manual package update for clients that did
not receive a trusted bridge release.

## Provider and macOS release configuration

Release builds use the same repository secrets on Linux, Windows, macOS, and
Android. Add each value once under **Settings → Secrets and variables → Actions**:

| Secret | Purpose |
|---|---|
| `TMDB_API_KEY` | TMDB v3 client key used for catalog metadata |
| `SUPABASE_URL` | Cove's Supabase project URL |
| `SUPABASE_PUBLISHABLE_KEY` | Public/publishable Supabase client key |
| `TRAKT_CLIENT_ID` | Trakt OAuth client id |
| `TRAKT_CLIENT_SECRET` | Trakt OAuth client secret |

The Gradle build reads those values directly from its environment and writes the
client configuration into each package. Do not configure a Supabase service-role
key or JWT secret: Cove clients neither need nor accept server authority.

The Apple-silicon DMG additionally requires:

| Secret | Purpose |
|---|---|
| `MACOS_CERTIFICATE_BASE64` | Base64 PKCS#12 containing a Developer ID Application certificate and private key |
| `MACOS_CERTIFICATE_PASSWORD` | PKCS#12 export password |
| `APPLE_NOTARIZATION_ID` | Apple account used by `notarytool` |
| `APPLE_NOTARIZATION_PASSWORD` | App-specific Apple password |
| `APPLE_TEAM_ID` | Apple Developer team identifier |

The release job imports the certificate into an ephemeral runner keychain,
signs the Compose app and bundled libmpv closure, notarizes the final DMG, and
publishes it only after Apple's ticket validates. None of these values belongs
in `.env`, source files, Gradle properties, or the Git history.
