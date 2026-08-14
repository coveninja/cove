# Application updates

Cove's in-process updater is available for Windows installer and portable
builds, and for the Android phone/tablet APK. AUR installs remain owned by
`pacman`; standalone Flatpak bundles are replaced manually. macOS and Android TV
do not currently ship an updater.

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
signed targets and marker files. A pre-`1.0.0` Windows installation needs one
manual upgrade because those binaries do not contain the updater or mode marker.

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
