# Install on Linux

Cove publishes amd64 packages for Arch-based systems, Flatpak, and other Linux distributions.

## Arch Linux and CachyOS

Install the AUR package with your preferred helper:

```sh
yay -S cove-bin
```

The package is named `cove-bin`. Updates remain managed by `pacman` and your AUR workflow.

## Flatpak

Download `cove-linux-amd64.flatpak` from the [latest stable release](https://github.com/coveninja/cove/releases/latest), then run:

```sh
flatpak install --user cove-linux-amd64.flatpak
flatpak run io.github.coveninja.Cove
```

The standalone bundle is not a Flathub remote. Replace it manually when a new version is released.

## Other distributions

Download `cove-linux-amd64.tar.gz`. It includes its Java runtime but expects `libmpv` from the distribution. An installed `yt-dlp` is preferred for supported video extras.

The launcher expects Cove under `/usr`, so install the archive with:

```sh
sudo tar -xzf cove-linux-amd64.tar.gz -C /usr
cove
```

Replace a tarball installation manually when updating.

## Verify a download

Every Linux release asset has a `.sha256` file beside it. Run, for example:

```sh
sha256sum -c cove-linux-amd64.flatpak.sha256
```

If the checksum fails, delete the download and obtain it again from the official release.

