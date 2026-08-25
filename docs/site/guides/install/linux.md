# Install on Linux

Cove publishes amd64 packages for Arch-based systems, Flatpak, and other Linux distributions. Choose one installation method and continue updating it through the same channel.

## Requirements

- An amd64 Linux system
- A working graphical session and GPU driver
- `libmpv` for the AUR and tarball distributions
- Enough space for Cove's bundled Java runtime and local media caches

An installed `yt-dlp` is preferred for supported video extras. When none is available, Cove can provision its own copy unless **Keep yt-dlp up to date** is disabled.

## Arch Linux and CachyOS

Install the AUR package with your preferred helper:

```sh
yay -S cove-bin
```

The package is named `cove-bin`. Updates remain managed by `pacman` and your AUR workflow.

Launch Cove from the desktop menu or run `cove`. Remove it through the same package manager rather than deleting individual installed files.

## Flatpak

Download `cove-linux-amd64.flatpak` from the [latest stable release](https://github.com/coveninja/cove/releases/latest), then run:

```sh
flatpak install --user cove-linux-amd64.flatpak
flatpak run io.github.coveninja.Cove
```

The standalone bundle is not a Flathub remote. Replace it manually when a new version is released.

The Flatpak stores its application data under its sandbox. Removing the package does not necessarily remove that data unless the uninstall operation explicitly requests data deletion.

## Other distributions

Download `cove-linux-amd64.tar.gz`. It includes its Java runtime but expects `libmpv` from the distribution.

The launcher expects Cove under `/usr`, so install the archive with:

```sh
sudo tar -xzf cove-linux-amd64.tar.gz -C /usr
cove
```

Replace a tarball installation manually when updating.

The archive is built for the `/usr` prefix. Extracting it under another prefix leaves launcher paths pointing at the wrong application image. Keep the release archive or inspect its file list when removing a manual installation; do not recursively delete `/usr` or another shared prefix.

## Verify a download

Every Linux release asset has a `.sha256` file beside it. Run, for example:

```sh
sha256sum -c cove-linux-amd64.flatpak.sha256
```

If the checksum fails, delete the download and obtain it again from the official release.

## First launch and logs

A fixture or API key is not required in the packaged application. Complete the normal onboarding flow and add optional sources later.

Linux desktop logs are stored under `~/.config/cove/logs/`. Flatpak logs are under `~/.var/app/io.github.coveninja.Cove/config/cove/logs/`. See [Troubleshooting](../troubleshooting.md) before sharing them.

For update behavior by package type, read [Update Cove](../updates.md).
